# Scarlet 0.4.18-b2 Release Notes

Full localization (with Russian), a moderator **training mode**, and reliability watchdogs that stop Scarlet failing silently.

## What's New

### Multi-language desktop client — now with Russian

- The **entire desktop UI** is translated — menus, settings, the instance-creation wizard, moderation dialogs, avatar stats, advisories, and even the spoken **TTS callouts** (~590 strings). Building on 0.4.18-b1's foundation, English and Russian now ship complete; German/Spanish/Indonesian are seeded pilots that fall back to English for anything not yet done.
- The language setting is a **dropdown of native names** (System default / English / Deutsch / Español / Bahasa Indonesia / Русский), defaulting to your OS language and applied on restart.
- **Spoken callouts are localized too**, and a name written in the active language's script is spoken natively — a Russian voice reads a Cyrillic name as-is instead of romanizing it. Select a matching TTS voice to hear it.
- **Community translations** drop into the `lang/` folder with no rebuild, and a startup **linter** (plus a `langlint` command) flags missing keys and broken `{0}` placeholders before they reach users.

### Training mode for onboarding moderators

- **Settings → Training** enables an event simulator (Edit → *Simulate event (training)…*): fire watched-group joins, vote-to-kicks, suspicious names, watched avatars and more on demand, so a trainee learns on a screenshare instead of a live incident — nobody has to join a bad group.
- Simulated events run the **real** pipeline — player-list row, TTS callout, notifications, and a genuine Discord post with its ban/unban buttons — so the whole workflow, including **Discord tagging**, is practiceable. Everything is marked `[TRAINING]` and can never be mistaken for a real record.
- While training, Scarlet behaves as a **separate client**: the real instance is parked and kept updated in the background, actions on training players show real success feedback but make no VRChat call, and turning training off restores the live instance instantly.
- **`/set-training-channel`** keeps simulated posts out of your real audit log.

### Reliability: no more silent failures

- **Health watchdog** — if the VRChat log goes quiet while the game is running, or the VRChat session expires mid-run, Scarlet now says so (popup + optional Discord alert) and attempts automatic re-login, instead of quietly ceasing to moderate.
- **`/set-ops-alert-channel`** routes health alerts (session lost/recovered, log tailer stalled, hard rate-limiting, update available) to a staff channel, so problems surface in Discord, not just on the desktop.
- **Crash-safe saves** — watched lists and other data files write atomically and keep 10 dated backups in a `backups/` folder, so a crash mid-write can't truncate a blocklist.

### Smaller touches

- The **VRChat-API update check** now watches the `vrchatapi-java` GitHub tags as well as JitPack, tolerates version-scheme drift, and no longer stalls startup.
- Settings → Appearance gains **"Dim players who left (%)"** to control how faded departed rows appear.

## Fixes

- **Departed players no longer fade to invisible down the list.** The dimming was being re-applied on top of itself across a run of left rows, so the bottom ones went nearly black; it's now a fixed amount regardless of position (and configurable).
- **Startup no longer stalls** waiting on the VRChat-API version check, which now runs off the launch path.

## Upgrade notes

- No settings migration needed. Data files gain a `backups/` folder beside them automatically.
- **Set your language** in Settings → Appearance → the language dropdown (or leave it on *System default*). For localized TTS callouts, also select a matching voice in Settings → Text-to-Speech.
- **New optional Discord commands** (both require Manage Server): `/set-ops-alert-channel` for health alerts, and `/set-training-channel` for training events. Set them to use those features; leave them unset and nothing changes.
- **Training mode** is off by default. Turn it on in Settings → Training only when running drills.
