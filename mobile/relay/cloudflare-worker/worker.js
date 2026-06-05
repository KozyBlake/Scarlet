let cachedAccessToken = null;
let cachedAccessTokenExpiresAt = 0;
let cachedPrivateKey = null;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "scarlet-mobile-relay" });
    }

    if (request.method === "POST" && url.pathname === "/pair") {
      return handlePair(request, env);
    }

    if (request.method === "POST" && url.pathname === "/event") {
      return handleEvent(request, env);
    }

    return json({ ok: false, error: "Not found" }, 404);
  },
};

async function handlePair(request, env) {
  const body = await request.json();
  const pairing = body.pairing || body;
  const deviceIn = body.device || {};

  if (pairing.kind !== "scarlet-mobile-pairing") {
    return json({ ok: false, error: "Not a Scarlet mobile pairing payload." }, 400);
  }

  const instanceId = clean(pairing.instanceId);
  const pairingId = clean(pairing.pairingId);
  const pairingSecret = clean(pairing.pairingSecret);
  const expiresAt = clean(pairing.expiresAt);
  const fcmToken = clean(deviceIn.fcmToken || deviceIn.pushToken);

  if (!instanceId || !pairingId || !pairingSecret || !expiresAt || !fcmToken) {
    return json({ ok: false, error: "Missing instanceId, pairing token, expiry, or FCM token." }, 400);
  }

  if (!(await isValidPairingSignature(pairing, env))) {
    return json({ ok: false, error: "Pairing signature is invalid. Check Scarlet's mobile relay auth token." }, 401);
  }

  if (Date.now() > Date.parse(expiresAt)) {
    return json({ ok: false, error: "Pairing QR has expired. Generate a new QR in Scarlet." }, 410);
  }

  const record = await getInstanceRecord(env, instanceId);
  const device = {
    id: clean(deviceIn.id) || crypto.randomUUID(),
    name: clean(deviceIn.name) || "Android",
    platform: clean(deviceIn.platform) || "android",
    fcmToken,
    pairedAt: new Date().toISOString(),
    lastSeenAt: new Date().toISOString(),
    pairingId,
    notificationTypes: normalizeNotificationTypes(deviceIn.notificationTypes || pairing.notificationDefaults),
  };

  record.devices = (record.devices || []).filter((existing) =>
    existing && existing.fcmToken !== fcmToken && existing.id !== device.id);
  record.devices.push(device);
  record.updatedAt = new Date().toISOString();

  await putInstanceRecord(env, instanceId, record);

  return json({
    ok: true,
    instanceId,
    deviceId: device.id,
    deviceName: device.name,
  });
}

async function handleEvent(request, env) {
  if (!isAuthorizedEventRequest(request, env)) {
    return json({ ok: false, error: "Unauthorized" }, 401);
  }

  const envelope = await request.json();
  const event = envelope.event || envelope;
  const instanceId = clean(event.instanceId || envelope.instanceId);

  if (!instanceId) {
    return json({ ok: false, error: "Event has no instanceId." }, 400);
  }

  const record = await getInstanceRecord(env, instanceId);
  const devices = record.devices || [];
  const keptDevices = [];
  let sent = 0;
  let skipped = 0;
  let failed = 0;

  for (const device of devices) {
    if (!device || !device.fcmToken) continue;
    if (!deviceWantsEvent(device, event)) {
      keptDevices.push(device);
      skipped++;
      continue;
    }

    const result = await sendFcm(env, device.fcmToken, event);
    if (result.ok) {
      sent++;
      device.lastSeenAt = new Date().toISOString();
      keptDevices.push(device);
    } else if (result.dropDevice) {
      failed++;
    } else {
      failed++;
      keptDevices.push(device);
    }
  }

  if (keptDevices.length !== devices.length) {
    record.devices = keptDevices;
    record.updatedAt = new Date().toISOString();
    await putInstanceRecord(env, instanceId, record);
  }

  return json({ ok: true, instanceId, sent, skipped, failed });
}

function isAuthorizedEventRequest(request, env) {
  const expected = clean(env.SCARLET_RELAY_TOKEN);
  if (!expected) return true;

  const url = new URL(request.url);
  const queryToken = clean(url.searchParams.get("token"));
  if (queryToken === expected) return true;

  const auth = clean(request.headers.get("Authorization"));
  if (!auth) return false;
  const token = auth.toLowerCase().startsWith("bearer ")
    ? auth.substring(7).trim()
    : auth;
  return token === expected;
}

async function isValidPairingSignature(pairing, env) {
  const token = clean(env.SCARLET_RELAY_TOKEN);
  if (!token) return true;
  const signature = clean(pairing.signature);
  if (!signature) return false;
  const expected = await hmacSha256Hex(token, pairingSignaturePayload(
    pairing.instanceId,
    pairing.pairingId,
    pairing.pairingSecret,
    pairing.expiresAt));
  return timingSafeEqual(signature, expected);
}

function pairingSignaturePayload(instanceId, pairingId, pairingSecret, expiresAt) {
  return [
    "scarlet-mobile-pairing",
    String(instanceId),
    String(pairingId),
    String(pairingSecret),
    String(expiresAt),
  ].join("|");
}

async function hmacSha256Hex(key, value) {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(key),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", cryptoKey, new TextEncoder().encode(value));
  return hex(new Uint8Array(signature));
}

function hex(bytes) {
  let out = "";
  for (const byte of bytes) out += byte.toString(16).padStart(2, "0");
  return out;
}

function timingSafeEqual(a, b) {
  a = String(a || "");
  b = String(b || "");
  let diff = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}

async function getInstanceRecord(env, instanceId) {
  return await env.SCARLET_MOBILE.get(`instance:${instanceId}`, "json") || {
    instanceId,
    devices: [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

async function putInstanceRecord(env, instanceId, record) {
  await env.SCARLET_MOBILE.put(`instance:${instanceId}`, JSON.stringify(record));
}

function normalizeNotificationTypes(value) {
  const out = {};
  if (!value || typeof value !== "object") return out;
  for (const [key, enabled] of Object.entries(value)) {
    if (typeof enabled === "boolean") out[key] = enabled;
  }
  return out;
}

function deviceWantsEvent(device, event) {
  const type = clean(event.type);
  if (!type || !device.notificationTypes) return true;
  return device.notificationTypes[type] !== false;
}

async function sendFcm(env, token, event) {
  const accessToken = await getAccessToken(env);
  const projectId = clean(env.FCM_PROJECT_ID);
  const title = clean(event.title) || clean(event.typeTitle) || "Scarlet alert";
  const body = clean(event.body) || "An alert was received from Scarlet.";
  const data = stringifyData({
    eventId: event.id,
    type: event.type,
    severity: event.severity,
    title,
    body,
    instanceId: event.instanceId,
    groupId: event.groupId,
    location: event.location,
    subjectId: event.subjectId,
    subjectName: event.subjectName,
  });

  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token,
        notification: { title, body },
        data,
        android: {
          priority: "high",
          notification: {
            channel_id: "scarlet_alerts",
            tag: clean(event.id) || clean(event.type) || "scarlet",
          },
        },
      },
    }),
  });

  if (response.ok) return { ok: true };

  const text = await response.text();
  const dropDevice = response.status === 404
    || text.includes("UNREGISTERED")
    || text.includes("registration token is not a valid");
  console.warn("FCM send failed", response.status, text);
  return { ok: false, dropDevice };
}

async function getAccessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccessToken && cachedAccessTokenExpiresAt - 60 > now) {
    return cachedAccessToken;
  }

  const header = base64UrlJson({ alg: "RS256", typ: "JWT" });
  const claim = base64UrlJson({
    iss: env.FCM_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  });
  const unsignedJwt = `${header}.${claim}`;
  const key = await getPrivateKey(env);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsignedJwt));
  const jwt = `${unsignedJwt}.${base64UrlBytes(new Uint8Array(signature))}`;

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  if (!tokenResponse.ok) {
    throw new Error(`OAuth token request failed: ${tokenResponse.status} ${await tokenResponse.text()}`);
  }

  const tokenJson = await tokenResponse.json();
  cachedAccessToken = tokenJson.access_token;
  cachedAccessTokenExpiresAt = now + Number(tokenJson.expires_in || 3600);
  return cachedAccessToken;
}

async function getPrivateKey(env) {
  if (cachedPrivateKey) return cachedPrivateKey;
  const pem = String(env.FCM_PRIVATE_KEY || "").replace(/\\n/g, "\n");
  const body = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  const bytes = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  cachedPrivateKey = await crypto.subtle.importKey(
    "pkcs8",
    bytes,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]);
  return cachedPrivateKey;
}

function stringifyData(values) {
  const out = {};
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null) out[key] = String(value);
  }
  return out;
}

function base64UrlJson(value) {
  return base64UrlBytes(new TextEncoder().encode(JSON.stringify(value)));
}

function base64UrlBytes(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function clean(value) {
  if (value === undefined || value === null) return null;
  value = String(value).trim();
  return value.length === 0 ? null : value;
}

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
