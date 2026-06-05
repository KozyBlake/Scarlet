/**
 * Scarlet Mobile Relay — Node.js SSE server
 *
 * Auth is per-instance using the pairing secret from the QR scan.
 * No shared token is needed or stored in the APK.
 *
 * Environment variables:
 *   PORT   — listening port (default: 3000)
 *
 * Endpoints:
 *   GET  /scarlet/mobile/health   — liveness check
 *   GET  /scarlet/mobile/events   — SSE stream (?instance=ID, Authorization: Bearer <pairingSecret>)
 *   POST /scarlet/mobile/event    — receive event from Scarlet (Authorization: Bearer <pairingSecret>)
 *   POST /scarlet/mobile/pair     — register a paired device
 */

'use strict';

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.PORT || '3000', 10);
const PAIRINGS_FILE = path.join(__dirname, 'pairings.json');

// instanceId -> Set of active SSE response streams
const clients = new Map();

// instanceId -> { secretHash: string, pairedAt: string }
// Matches Java: Base64.getUrlEncoder().withoutPadding().encodeToString(SHA-256 digest)
const pairings = new Map();

// ── Persistence ───────────────────────────────────────────────────────────────

function loadPairings() {
    try {
        const data = JSON.parse(fs.readFileSync(PAIRINGS_FILE, 'utf8'));
        for (const [k, v] of Object.entries(data)) {
            if (k && v && v.secretHash) pairings.set(k, v);
        }
        console.log(`Loaded ${pairings.size} pairing(s) from disk`);
    } catch (_) {
        // No file yet — fresh start
    }
}

function savePairings() {
    const obj = {};
    for (const [k, v] of pairings) obj[k] = v;
    try {
        fs.writeFileSync(PAIRINGS_FILE, JSON.stringify(obj, null, 2), 'utf8');
    } catch (ex) {
        console.error('Failed to save pairings:', ex.message);
    }
}

// ── Auth ──────────────────────────────────────────────────────────────────────

// Must match Java: Base64.getUrlEncoder().withoutPadding().encodeToString(SHA-256)
function sha256(value) {
    return crypto.createHash('sha256').update(value, 'utf8').digest('base64url');
}

function timingSafeStringEqual(a, b) {
    if (!a || !b) return false;
    const ba = Buffer.from(String(a));
    const bb = Buffer.from(String(b));
    if (ba.length !== bb.length) {
        // consume time even on length mismatch
        crypto.timingSafeEqual(ba, ba);
        return false;
    }
    return crypto.timingSafeEqual(ba, bb);
}

function checkInstanceAuth(instanceId, secret) {
    if (!instanceId || !secret) return false;
    const record = pairings.get(instanceId);
    if (!record || !record.secretHash) return false;
    return timingSafeStringEqual(sha256(secret), record.secretHash);
}

function extractBearer(req) {
    const auth = (req.headers['authorization'] || '').trim();
    if (auth.toLowerCase().startsWith('bearer ')) return auth.slice(7).trim();
    return null;
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
    const frame = Buffer.from(
        'event: scarlet\ndata: ' + JSON.stringify(eventData).replace(/\n/g, '\\n') + '\n\n',
        'utf8');
    let sent = 0;
    for (const res of [...set]) {
        try {
            res.write(frame);
            sent++;
        } catch (_) {
            // socket already gone — cleanup happens in the close handler
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
    const pathname = u.pathname;
    const method = req.method.toUpperCase();

    res.setHeader('Access-Control-Allow-Origin', '*');

    // ── Health ────────────────────────────────────────────────────────────────
    if (method === 'GET' && pathname === '/scarlet/mobile/health') {
        let connectedInstances = 0;
        let connectedClients = 0;
        for (const set of clients.values()) {
            connectedInstances++;
            connectedClients += set.size;
        }
        return sendJson(res, 200, {
            ok: true,
            service: 'scarlet-mobile-relay',
            pairedInstances: pairings.size,
            connectedInstances,
            connectedClients,
        });
    }

    // ── SSE stream (Android app connects here) ────────────────────────────────
    if (method === 'GET' && pathname === '/scarlet/mobile/events') {
        const instanceId = (u.searchParams.get('instance') || '').trim();
        const secret = extractBearer(req);

        if (!instanceId) return sendJson(res, 400, { ok: false, error: 'Missing instance' });
        if (!checkInstanceAuth(instanceId, secret)) return sendJson(res, 403, { ok: false, error: 'Unauthorized' });

        res.writeHead(200, {
            'Content-Type': 'text/event-stream; charset=utf-8',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'X-Accel-Buffering': 'no',
        });
        res.write('event: hello\ndata: {"ok":true}\n\n');

        addClient(instanceId, res);
        req.on('close', () => removeClient(instanceId, res));
        req.on('error', () => removeClient(instanceId, res));
        return;
    }

    // ── Receive event from Scarlet desktop ────────────────────────────────────
    if (method === 'POST' && pathname === '/scarlet/mobile/event') {
        let body;
        try {
            body = JSON.parse(await readBody(req));
        } catch (_) {
            return sendJson(res, 400, { ok: false, error: 'Invalid JSON' });
        }

        const event = body.event || body;
        const instanceId = ((event.instanceId || body.instanceId) || '').trim();
        const secret = extractBearer(req);

        if (!instanceId) return sendJson(res, 400, { ok: false, error: 'Missing instanceId' });
        if (!checkInstanceAuth(instanceId, secret)) return sendJson(res, 403, { ok: false, error: 'Unauthorized' });

        const sent = broadcast(instanceId, event);
        console.log(`[event] type=${event.type} instance=${instanceId} sent=${sent}`);
        return sendJson(res, 200, { ok: true, instanceId, sent });
    }

    // ── Register paired device ────────────────────────────────────────────────
    if (method === 'POST' && pathname === '/scarlet/mobile/pair') {
        let body;
        try {
            body = JSON.parse(await readBody(req));
        } catch (_) {
            return sendJson(res, 400, { ok: false, error: 'Invalid JSON' });
        }

        const instanceId = (body.instanceId || '').trim();
        const pairingSecret = (body.pairingSecret || '').trim();

        if (!instanceId || !pairingSecret) {
            return sendJson(res, 400, { ok: false, error: 'Missing instanceId or pairingSecret' });
        }

        // Store hash only — the plain secret never persists on the relay
        pairings.set(instanceId, {
            secretHash: sha256(pairingSecret),
            pairedAt: new Date().toISOString(),
        });
        savePairings();

        const deviceId = (body.device && body.device.id)
            ? body.device.id
            : 'mob_' + crypto.randomBytes(6).toString('hex');

        console.log(`[pair] instance=${instanceId} device=${deviceId}`);
        return sendJson(res, 200, { ok: true, instanceId, deviceId });
    }

    sendJson(res, 404, { ok: false, error: 'Not found' });
});

loadPairings();
server.listen(PORT, () => {
    console.log(`Scarlet mobile relay listening on port ${PORT}`);
});
