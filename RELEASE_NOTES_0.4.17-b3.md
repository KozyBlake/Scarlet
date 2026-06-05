# Scarlet 0.4.17-b3 Release Notes

## What's New

### Scarlet Companion — Android Push Notifications

This release ships the **Scarlet Companion** Android app, allowing you to receive Scarlet alerts on your phone from anywhere in the world — no accounts, no third-party services, no domain required.

#### How it works

Scarlet runs a relay through a lightweight Node.js server. When something happens in your instance, Scarlet posts the event to the relay, which forwards it to your phone over a persistent connection. This works whether your phone is on the same Wi-Fi as your PC or on the other side of the world.

#### Setup (one time)

1. **Install the APK** on your Android phone (included in this release as `ScarletCompanion-debug.apk`)
2. **Enable Mobile companion** in Scarlet's settings
3. Click **Create** next to *Create mobile pairing QR*
4. **Scan the QR** with the Scarlet Companion app
5. Tap **Start Direct Listener** in the app
6. Done — notifications will now arrive on your phone

#### Notification types

Each alert type has its own sound from the previously known BanLogger:

| Alert | Sound | Color in log |
|-------|-------|--------------|
| Watched user joined | `BL_SFX_ALERT` | 🔴 Red |
| Watched group joined | `BL_SFX_ALERT` | 🔴 Red |
| Watched avatar detected | `BL_SFX_SIREN_CHIRP` | 🟠 Orange |
| Vote-to-kick started | `BL_SFX_SIREN_CHIRP` | 🟠 Orange |
| Moderation action | `BL_SFX_NOTICE` | 🟡 Yellow |
| Suspicious pronouns | `BL_SFX_NOTICE` | 🔵 Blue |
| Mixed-character name | `BL_SFX_NOTICE` | 🔵 Blue |
| Staff join/leave | `BL_SFX_NOTICE` | 🟢 Green |
| New player | `BL_SFX_NOTICE` | 🟣 Purple |

#### Notification settings

In Scarlet's settings under **Mobile companion** you can control:
- Which alert types send notifications (watched users, groups, avatars, votes-to-kick, moderation, staff, new players, mixed names, suspicious pronouns)
- Minimum severity threshold (INFO = everything, WATCH = most things, WARNING = important only, CRITICAL = only critical)

#### In-app alert log

The Scarlet Companion app keeps a persistent log of every alert received, regardless of whether it showed as a notification. Tap **View Alert Log** in the app to review everything that happened. Alerts are color-coded by type and include timestamps. Tap **Clear Alert Log** to wipe it.

#### High-volume instances

In busy instances with many joins and leaves, Android's notification rate limit is handled automatically. After 20 notifications, all active alerts are cleared to reset the counter and notifications continue normally. Every alert is always written to the in-app log so nothing is ever lost.

#### Network switching

The app seamlessly switches between your home network (direct LAN, lowest latency) and mobile data (relay) without any user action. Expect a 10-15 second gap when switching networks while the app reconnects.

#### Privacy

- No accounts required — pairing uses a one-time QR code
- The relay stores only a cryptographic hash of your pairing secret, never the secret itself
- Each Scarlet instance has a unique ID — events are only delivered to the phone that scanned your QR
- The relay URL is shared infrastructure but each user's alerts are fully isolated

---

## Other Changes

- Fixed notification channels being stale after app updates — channels are now versioned and old ones are cleaned up automatically on app start, no clearing app data needed
- Fixed notifications not arriving when switching from Wi-Fi to mobile data
- Fixed pairing failing silently when no Firebase was configured
- Fixed watched group/user notifications being filtered by the default severity threshold
- Desktop mobile settings simplified — only relevant options shown to users

---

## Files in this release

| File | Description |
|------|-------------|
| `scarlet-0.4.17-b3.jar` | Scarlet desktop application |
| `scarlet-0.4.17-b3-android.jar` | Scarlet Android application|
| `ScarletCompanion-debug.apk` | Android companion app |

## Installing the Android APK

The APK is a debug build signed with a debug key. To install:

1. On your Android phone go to **Settings → Apps → Special app access → Install unknown apps**
2. Allow your browser or file manager to install APKs
3. Open the APK file and tap **Install**

Samsung devices: if installation is blocked, go to **Settings → Biometrics and security → Install unknown apps**.
