/**
 * Scarlet Mobile Relay — Node.js SSE server
 *
 * Scarlet (desktop) POSTs events here → forwarded to connected Android apps via SSE.
 * No Firebase or third-party push service required.
 *
 * Environment variables:
 *   PORT                  — listening port (default: 3000)
 *   SCARLET_RELAY_TOKEN   — shared secret; leave unset to disable auth (not recommended)
 *
 * Endpoints:
 *   GET  /scarlet/mobile/health           — liveness check
 *   GET  /scarlet/mobile/events           — SSE stream for Android app (?token=TOKEN&instance=ID)
 *   POST /scarlet/mobile/event            — receive event from Scarlet desktop
 *   POST /scarlet/mobile/pair             — pairing acknowledgement (no FCM storage needed)
 */

'use strict';

const http = require('http');
const crypto = require('crypto');

const PORT = parseInt(process.env.PORT || '3000', 10);
const RELAY_TOKEN = (process.env.SCARLET_RELAY_TOKEN || '').trim();

// instanceId -> Set of active SSE response streams
const clients = new Map();

// ── Auth ──────────────────────────────────────────────────────────────────────

function checkToken(token) {
    if (!RELAY_TOKEN) return true;
    if (!token) return false;
    const a = Buffer.from(String(token).trim());
    const b = Buffer.from(RELAY_TOKEN);
    if (a.length !== b.length) {
        // still consume time to prevent length-based timing leak
        crypto.timingSafeEqual(a.length > 0 ? a : Buffer.alloc(1), a.length > 0 ? a : Buffer.alloc(1));
        return false;
    }
    return crypto.timingSafeEqual(a, b);
}

function extractToken(req) {
    const auth = (req.headers['authorization'] || '').trim();
    if (auth.toLowerCase().startsWith('bearer ')) return auth.slice(7).trim();
    const u = new URL(req.url, 'http://localhost');
    return (u.searchParams.get('token') || '').trim();
}

// ── SSE client management ─────────────────────────────────────────────────────

function addClient(instanceId, res) {
    if (!clients.has(instanceId)) clients.set(instanceId, new Set());
    clients.get(instanceId).add(res);
    console.log(`[+] SSE client for ${instanceId} — total: ${clients.get(instanceId).size}`);
}

function removeClient(instanceId, res) {
    const set = clients.get(instanceId);
    if (!set) return;
    set.delete(res);
    console.log(`[-] SSE client for ${instanceId} — remaining: ${set.size}`);
    if (set.size === 0) clients.delete(instanceId);
}

function broadcast(instanceId, eventData) {
    const set = clients.get(instanceId);
    if (!set || set.size === 0) return 0;
    // Inline JSON into SSE frame; collapse any embedded newlines
    const frame = Buffer.from(
        'event: scarlet\ndata: ' + JSON.stringify(eventData).replace(/\n/g, '\\n') + '\n\n',
        'utf8');
    let sent = 0;
    for (const res of [...set]) {
        try {
            res.write(frame);
            sent++;
        } catch (_) {
            // socket already gone — cleanup happens in the 'close' handler
        }
    }
    return sent;
}

// ── HTTP helpers ──────────────────────────────────────────────────────────────

function sendJson(res, status, body) {
    const data = JSON.stringify(body);
    res.writeHead(status, {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(data),
    });
    res.end(data);
}

function readBody(req) {
    return new Promise((resolve, reject) => {
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
        req.on('error', reject);
    });
}

// ── Request router ────────────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, 'http://localhost');
    const path = u.pathname;
    const method = req.method.toUpperCase();

    res.setHeader('Access-Control-Allow-Origin', '*');

    // ── Health ────────────────────────────────────────────────────────────────
    if (method === 'GET' && path === '/scarlet/mobile/health') {
        let connectedInstances = 0;
        let connectedClients = 0;
        for (const set of clients.values()) {
            connectedInstances++;
            connectedClients += set.size;
        }
        return sendJson(res, 200, {
            ok: true,
            service: 'scarlet-mobile-relay',
            connectedInstances,
            connectedClients,
        });
    }

    // ── SSE stream (Android app connects here) ────────────────────────────────
    if (method === 'GET' && path === '/scarlet/mobile/events') {
        const token = (u.searchParams.get('token') || '').trim();
        const instanceId = (u.searchParams.get('instance') || '').trim();

        if (!checkToken(token)) return sendJson(res, 403, { ok: false, error: 'Unauthorized' });
        if (!instanceId) return sendJson(res, 400, { ok: false, error: 'Missing instance' });

        res.writeHead(200, {
            'Content-Type': 'text/event-stream; charset=utf-8',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no', // prevent nginx from buffering the stream
        });
        res.write('event: hello\ndata: {"ok":true}\n\n');

        addClient(instanceId, res);
        req.on('close', () => removeClient(instanceId, res));
        req.on('error', () => removeClient(instanceId, res));
        return; // connection stays open
    }

    // ── Receive event from Scarlet desktop ────────────────────────────────────
    if (method === 'POST' && path === '/scarlet/mobile/event') {
        if (!checkToken(extractToken(req))) return sendJson(res, 403, { ok: false, error: 'Unauthorized' });

        let body;
        try {
            body = JSON.parse(await readBody(req));
        } catch (_) {
            return sendJson(res, 400, { ok: false, error: 'Invalid JSON' });
        }

        const event = body.event || body;
        const instanceId = ((event.instanceId || body.instanceId) || '').trim();
        if (!instanceId) return sendJson(res, 400, { ok: false, error: 'Missing instanceId' });

        const sent = broadcast(instanceId, event);
        console.log(`[event] type=${event.type} instance=${instanceId} sent=${sent}`);
        return sendJson(res, 200, { ok: true, instanceId, sent });
    }

    // ── Pairing acknowledgement ───────────────────────────────────────────────
    if (method === 'POST' && path === '/scarlet/mobile/pair') {
        let body;
        try {
            body = JSON.parse(await readBody(req));
        } catch (_) {
            return sendJson(res, 400, { ok: false, error: 'Invalid JSON' });
        }
        const deviceId = (body.device && body.device.id)
            ? body.device.id
            : 'mob_' + crypto.randomBytes(6).toString('hex');
        console.log(`[pair] device=${deviceId}`);
        return sendJson(res, 200, { ok: true, deviceId });
    }

    sendJson(res, 404, { ok: false, error: 'Not found' });
});

server.listen(PORT, () => {
    console.log(`Scarlet mobile relay listening on port ${PORT}`);
    if (!RELAY_TOKEN) console.warn('WARNING: SCARLET_RELAY_TOKEN is not set — relay is unauthenticated');
});
