# Scarlet 0.4.18-b2

Full desktop localization with Russian, a safe moderator-training environment, experimental multi-group moderation, and a major reliability pass for health monitoring and mobile notifications.

## Highlights

- **Moderate multiple VRChat groups from one Scarlet window** with the new experimental, opt-in multi-group mode.
- **Use Scarlet in English or Russian** across the complete desktop experience, including spoken advisory callouts.
- **Train moderators safely** with simulated events that exercise the real presentation and Discord workflows without touching VRChat.
- **Stay ahead of silent failures** with session and log-tailer watchdogs, Discord operations alerts, crash-safe saves, and much more reliable mobile delivery.

> [!WARNING]
> Multi-group mode is experimental and remains intentionally rate-limited when several groups share one VRChat account. Use separate accounts per group to scale beyond that limit.

## What's new

### Experimental multi-group support

One Scarlet process can now moderate multiple VRChat groups at once. Every group has its own data directory, VRChat session, and Discord bot, while appearing as a tab in one shared application window. Existing single-group installs and launches are unchanged.

- Enable **Settings → Interface → Multi-group mode** and restart to activate it. Your current group continues to use the base data folder; additional groups are discovered from subfolders in `groups/`.
- Use **File → Add group from this one** to clone the current configuration into a new group slot and provide its group ID. The form can also collect separate VRChat and Discord credentials when those options are enabled.
- Operational data—including watch lists, sessions, and audit history—is never copied. Temporary migration credentials are moved into the new group’s secure store on first login and then cleared.
- Groups sharing one VRChat account now cooperate through a shared API rate limiter; groups with separate accounts receive separate API budgets. Discord moderation posts include their group in the footer so staff can distinguish activity in shared channels.
- Groups may optionally have their own VRChat account and/or Discord bot token. Shared base configuration with per-group overrides is still in progress.

### Safer configuration migration

The import dialog now includes **Carry over Discord channels**, enabled by default. Turn it off to retain the bot token, guild, roles, and other imported settings while clearing the old audit/log-channel routing—useful when moving a configuration to a different Discord server. Legacy bundles retain their previous import behavior.

### Complete desktop localization

The desktop client is now fully localized in **English and Russian**, covering roughly 590 message keys: the splash screen, menus, settings, instance wizard, moderation dialogs and results, avatar statistics, import/export and update dialogs, status bar, and spoken advisory/TTS messages.

- Scarlet follows the system language by default; `ui_language` can override it at startup.
- The language setting is now a native-name dropdown: `System default`, `English`, `Deutsch`, `Español`, `Bahasa Indonesia`, and `Русский`. New `messages_<lang>.properties` files are discovered automatically.
- German, Spanish, and Indonesian remain seeded pilot translations and fall back to English where incomplete.
- Discord bot messages remain in English for now, although interactions already resolve against the invoking user’s Discord locale.
- Spoken callouts are localized too. Names written in the active language’s native script are spoken as-is; other scripts are romanized with a localized script label. Select a matching installed TTS voice for the best result.
- A new startup validator and `langlint` CLI command check community translation files for missing/unknown keys, MessageFormat errors, and broken placeholders.

### Moderator training mode

**Settings → Training** now provides a safe simulator for onboarding and practice. Launch **Edit → Simulate event (training)…** or use the `simulate` CLI command to generate watched-group/user joins, new accounts, mixed-character names, suspicious pronouns, watched avatars, vote-to-kicks, and ordinary joins/leaves.

- Simulated events run through the genuine presentation and Discord pipelines: player-list advisories, TTS, desktop/mobile notifications, Discord posts, and ban/unban buttons.
- All Discord- and archive-bound training activity is marked `[TRAINING]`, and simulated users use the `usr_training-` ID namespace.
- While training is enabled, Scarlet presents a separate empty instance and parks the live player list in the background. Disabling training restores the live instance exactly as it is then; no live activity is lost.
- Training-player actions provide the normal success feedback but never make VRChat calls. Real actions on real users remain live.
- Set a dedicated training Discord channel with `/set-training-channel`. If none is set, training posts are dropped instead of reaching the live audit log, and training users stay out of the live-instance roster embed.

### Safer data and operational monitoring

- Small JSON data files now use atomic saves and retain the latest 10 dated backups in a sibling `backups/` folder. This covers watch lists, moderation tags, settings, Discord configuration, mobile devices, and pronoun lists; several files are now explicitly UTF-8 as well.
- A new watchdog warns when VRChat is running but no log line arrives for five minutes, and reports recovery when log traffic resumes.
- Scarlet now actively verifies the VRChat session about every five minutes. A genuine `401` triggers an alert and unattended credential/TOTP re-login attempts every 10 minutes; ordinary network failures are not treated as lost authentication.
- Configure `/set-ops-alert-channel` to send session, log-tailer, hard rate-limit, update-available, and VRChat API-version alerts to Discord.

### Mobile companion visibility and reachability

- **Settings → Mobile Companion → Show mobile delivery status** now shows direct-LAN listener status and port, live event streams and backlog, Firebase configuration, and device-level transport, enabled state, and recent delivery/failure information.
- Set `mobile_fcm_service_account` to a Firebase service-account JSON path to enable FCM push delivery outside the LAN/relay. Leaving it blank preserves Firebase-free behavior, and unusable transports now record why they cannot be reached.

### Other additions

- **Settings → Appearance → Dim players who left (%)** controls departed-row dimming live; `0` disables dimming and the default is `35`.

## Improvements

- The VRChat API update check now compares both JitPack metadata and GitHub tags, taking the newest valid version from either source. It handles common version-format differences and longer JitPack cold-build response times.
- The credits list now displays contributors as `Name — Role` and includes the new translation contributors.

## Fixes

### Desktop and startup

- Departed players no longer become progressively darker down the player list; each row is now dimmed from the base text color by the configured amount.
- The VRChat API version check now runs off the startup path, so a slow JitPack response can no longer delay Scarlet launching.

### Mobile delivery reliability

- Long-lived phone event streams no longer consume Scarlet’s shared moderation worker pool. The mobile listener and notification delivery now use dedicated pools, preventing paired devices from delaying audit polling, VRChat calls, moderation posts, or other alerts.
- A phone that silently leaves Wi-Fi can no longer block alert delivery for every device behind it. Each connection now has an independent send queue; stalled connections are dropped and reconnect on their own.
- Idle connections are kept alive with a 15-second heartbeat. The companion app reconnects after 45 seconds of silence instead of waiting indefinitely on a dead connection.
- Transient notification-send failures are retried up to five times with increasing backoff (2s, 5s, 15s, and 45s); genuine rejections still fail immediately.
- Firebase push is now correctly enabled when `mobile_fcm_service_account` is configured, and the missing Mobile Companion setting reference has been corrected.
- Devices are contacted independently, so one unreachable endpoint cannot hold up notifications to the rest.
- The companion app now uses capped reconnect backoff (1–60 seconds), resets after a stable connection, and reconnects immediately when the network returns.

## Upgrade notes

- Multi-group mode is disabled by default and requires a restart. It keeps your original base-folder group untouched.
- For training Discord posts, configure `/set-training-channel`; without one, training events are deliberately not sent to Discord.
- FCM delivery requires a valid Firebase service-account JSON path in `mobile_fcm_service_account`.
