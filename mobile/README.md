# Scarlet Mobile Companion

This folder contains Scarlet's Android companion paths for mobile
notifications.

## Anywhere Mode Without A Domain

If notifications must work from anywhere, Android needs a push provider. The
no-domain/no-website version is:

```text
one-time local QR pairing
-> Android gives Scarlet its Firebase Cloud Messaging token
-> Scarlet stores that token
-> Scarlet sends alerts directly to FCM
-> FCM wakes the Android app anywhere
```

There is no Worker, website, custom domain, or self-hosted relay in this mode.
The tradeoff is that Scarlet needs a Firebase service account JSON file so it
can call FCM's HTTP v1 API directly.

## Anywhere Setup

1. Create a Firebase project.
2. Add an Android app for package:

   `net.sybyline.scarlet.companion`

3. In `mobile/android/ScarletCompanion/app/src/main/res/values/firebase.xml`,
   fill in:

   - `firebase_app_id`
   - `firebase_api_key`
   - `firebase_project_id`
   - `firebase_sender_id`

4. In Firebase or Google Cloud, create a service account key JSON with access
   to Firebase Cloud Messaging.
5. In Scarlet settings:

   - Enable `Mobile companion`.
   - Set `Mobile FCM service account JSON` to the service account JSON path.
   - Keep `Mobile direct LAN connection` enabled for the one-time pairing.
   - Click `Create mobile pairing QR`.

6. Install the Android app, scan the QR while on the same Wi-Fi/VPN as the PC,
   and let the app register its FCM token with Scarlet.

After that pairing succeeds, the phone does not need to stay on the same
network for FCM notifications.

## Direct LAN Mode

Direct LAN mode does not use a website, custom domain, Worker, Firebase, or FCM.
Scarlet runs a tiny local event stream on the desktop. The Android app scans
Scarlet's QR code, stores the local event URL, starts a foreground listener,
and shows normal Android notifications for incoming Scarlet callouts.

```text
Scarlet desktop alert
-> local HTTP event stream on your PC
-> Android foreground listener
-> Android local notification
```

## Requirements

- The phone must be able to reach the PC:
  - same Wi-Fi/LAN, or
  - VPN/mesh network such as Tailscale or ZeroTier.
- Windows/macOS/Linux firewall must allow Scarlet's direct LAN port.
- Android must allow notification permission.
- The Android listener runs as a foreground service, so Android shows a small
  persistent "Scarlet Companion" connection notification while it listens.

That foreground service is the tradeoff for avoiding Firebase/FCM. Android
limits long-running background work, so a persistent local socket needs a
foreground service to stay reliable.

## Direct LAN Setup

1. Enable `Mobile companion`.
2. Keep `Mobile direct LAN connection` enabled.
3. Leave `Mobile direct LAN port` at `24892`, or choose another open port.
4. Leave `Mobile relay/webhook URL` blank if you do not want cloud delivery.
5. Click `Create mobile pairing QR`.

The QR includes direct LAN event URLs such as:

```text
http://192.168.1.25:24892/scarlet/mobile/events?token=...
```

The token is random and stored in Scarlet's `mobile_devices.json`.

## Android Setup

1. Open `mobile/android/ScarletCompanion` in Android Studio.
2. Build and install it on your Android phone.
3. Tap `Scan Scarlet QR`.
4. Accept notification permission.
5. Keep `Start Direct Listener` running.

## Optional Worker Relay

`relay/cloudflare-worker` is still included as an optional FCM relay for cases
where you do not want Scarlet to hold the Firebase service account key. Direct
FCM mode does not need it.
