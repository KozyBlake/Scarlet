
# Changelog

## 0.4.18

### Added

- **Live progress for Linux package installs.** Linux package installs now show a small progress dialog with an indeterminate bar, a masked input field for answering unexpected package-manager prompts, and an always-visible terminal output pane that streams the package manager's output live, so you can see it working instead of waiting in the dark. Privileged installs use `pkexec` when available; interactive/user-space package managers run inside a PTY-backed terminal embedded in Scarlet instead of opening an external terminal emulator. It's a reusable component (`PkexecInstaller`) so the TTS/voice installer and the other Linux install prompts can share it.
- **Desktop notifications (toasts) on Windows and Linux - no PowerShell.** Advisories can now raise native desktop notifications. Windows uses Java's built-in system-tray balloon, which Windows 10/11 surfaces as an Action Center toast; Linux uses `notify-send`; macOS uses `osascript`. PowerShell is deliberately never invoked, so one code path works across platforms and nothing pops a console window. A new **Settings -> Desktop Notifications** section adds a master on/off (default off), independent per-category toggles (watched users/groups/avatars, votes-to-kick, moderation, staff joins/leaves, new players, mixed-character names, suspicious pronouns), and a **Send desktop test notification** button. On Linux, if `notify-send` is missing, the test button and the first failed live toast offer to install the right libnotify package through the detected package manager. Toasts fire only for live joins, never when an instance first loads, and the tray icon is created lazily the first time a toast is shown.
- **Per-category advisory toggles.** A new **Settings -> Advisories** section controls watched users, watched groups, watched avatars, new players, mixed-character names, votes-to-kick, and suspicious pronouns separately. Turning a category off hides its Instance-table advisory and suppresses that category's TTS callout, so unwanted pronoun-style alerts no longer keep speaking after the visual advisory is disabled.
- **Guided audio-player install on Linux.** If local TTS playback is enabled but none of `pw-play`/`paplay`/`aplay` is present, Scarlet now offers to install one for you via your distro's package manager (reusing the same detector as the TTS/xdg-utils prompts), opening a terminal for the password. It targets `alsa-utils` (the universal `aplay`); `pw-play` and `paplay` already ship with PipeWire and PulseAudio respectively, so the prompt only appears on minimal setups. Until a player is installed, playback falls back to direct ALSA output.
- **More Linux TTS voices, installed by choice.** Linux TTS now discovers multiple local command-line engines instead of only `espeak`: Flite, Mimic, Pico/`pico2wave`, Festival/`text2wave`, eSpeak NG, and legacy eSpeak. Voice choices are shown as `engine/voice` (for example `flite/slt`, `pico2wave/en-US`, or `espeak-ng/en`), while old bare eSpeak voice names still fall back cleanly when they match. The startup installer only offers the default engine needed to make TTS work; a new **Settings -> Text-to-Speech -> Install Linux TTS voices** button lets Linux users explicitly choose extra repo-backed engines from their detected package manager.
- **Toggle for suspicious-pronoun detection.** A new "TTS: Flag suspicious pronouns" setting (Settings → Text-to-Speech, default on) turns the whole pronoun-detection path on or off — the Advisory entry, the spoken callout, the mobile alert, and the player-list highlight. Leave it on and use "TTS: Announce suspicious pronouns" to keep flagging while muting only the spoken line.
- **Declutter the main window.** Right-click the player-list header to show or hide columns — the same list as Edit → Columns, now where you'd expect it, and your choices are still remembered between launches. A new **View** menu lets you hide the **CLI** tab if you don't use it (`ui_show_cli_tab`).
- **Unlink VRChat accounts.** A new self-service `/unlink-vrchat-account` lets any member remove their own Discord↔VRChat link (handy for re-linking to re-test verification), and a staff-only `/unlink-vrchat-account-for` (requires Moderate Members) unlinks another member by Discord user and/or VRChat user. Both only clear the link record — they don't change Discord roles or VRChat group membership.
- **PIN-encrypted migration bundles (required).** Exporting a bundle now requires a PIN (at least 4 characters), which encrypts the whole bundle with AES-256-GCM (PBKDF2-SHA256 key) - the same primitives Scarlet uses for stored credentials. This matters because a bundle ships `global-prefs.key` alongside the credentials it decrypts, so an unencrypted bundle is effectively plaintext to anyone holding it; the PIN adds a layer whose key isn't in the file. The GCM tag also authenticates the bundle, so it can't be silently altered. Import prompts for the PIN and fails fast on a wrong one before touching any local data. A lost PIN cannot be recovered.
- **Move-out option when exporting.** The export dialog has an "I'm moving to another computer" checkbox. When set, after the bundle is written and verified Scarlet offers to remove this machine's data folder and stored credentials. The confirmation's destructive button is disabled and counts down (~10 seconds, "Remove all settings… 10… 9…") before it can be pressed, so the wipe can't be triggered reflexively; the "Keep" button is immediate. The removal runs during shutdown after all background work has stopped, so nothing can re-create the deleted files, and it's skipped if the bundle was saved inside the data folder.
- **One-file migration between machines.** A new **Settings -> Backup & Migration** section moves an entire Scarlet identity to another PC or operating system. **Export bundle** only copies from the source PC: it writes a single portable `.zip` containing Scarlet's data folder plus encrypted Java Preferences credentials exported as OS-independent XML, so Windows registry-backed credentials can import cleanly on Linux/macOS Java Preferences stores. **Import bundle** lets you choose data/config files, secure credentials/sign-ins, or both; data-only imports skip credential key files so the destination PC's existing sign-ins keep working, while credential imports include Java Preferences plus `global-prefs.key`. Import restores into the destination machine's correct data and Preferences locations, then quits Scarlet so the old in-memory session cannot overwrite the imported files. Because the credential-encryption key lives in the data folder rather than anything machine-bound, the Discord token, VRChat login, 2FA secret, session cookie and alternate credentials all decrypt unchanged on the destination, including Windows->Linux and Linux->Windows.

### Changed

- **Slimmer advisory text.** Multiple advisories in the Instance table now use a readable inline separator instead of newline boxes, suspicious-pronoun advisories collapse to a short "Suspicious pronouns" label, and watched avatars can populate the Advisory column when detected.
- **Pronoun toggles are visible in Settings.** "TTS: Announce suspicious pronouns" was previously only changeable by editing the settings file; both pronoun toggles now appear in the Text-to-Speech settings section, and are hidden in editions built without the pronoun feature.
- **Verification auto-invite no longer pre-checks group membership.** After a member verifies, Scarlet now sends the VRChat group invite directly instead of first querying their group membership status. Eligibility (VRChat age-verification or the manual-verification member role) is already decided at link time, and invite failures are now classified after VRChat responds.
- **Restart now relaunches Scarlet itself.** The existing Discord `/server-restart restart-now` action and the new CLI `reboot` / `restart` command now start a fresh Scarlet process from the current Java command line instead of only exiting with a restart code for an external wrapper. The relaunch uses the current JAR path or classpath, so it does not depend on the JAR filename, and it inherits the current terminal streams so console logging continues after reboot.

### Fixed

- **Linux TTS/package install actually runs now.** Installing eSpeak / extra TTS voices (and the related Linux package prompts) only knew how to open a terminal emulator, and it detected terminals with the external `which` command - which isn't installed on many modern/minimal distros - so on those systems nothing opened and it reported failure. Scarlet now installs through `pkexec` first - a graphical polkit password prompt, no terminal required, run as Scarlet's own child process so its exit code is trustworthy - and if terminal semantics are needed, Scarlet opens an embedded PTY-backed terminal inside its own installer dialog instead of launching `gnome-terminal`, `xfce4-terminal`, or another external emulator. All three Linux install prompts - TTS engines, the audio player (alsa-utils), and libnotify (`notify-send`) - now use this same path and verify the package appeared before reporting success.
- **Linux package installs no longer run user-space managers as root.** The progress installer now uses `pkexec` only for package managers marked as requiring sudo. Homebrew, Nix, AUR helpers, and other user-space managers run as the current user with the same live-output progress dialog, avoiding root-owned package caches or outright install failures.
- **AUR/helper password prompts now use an embedded real terminal.** Helpers such as `yay`, `paru`, and `pamac` may spawn `sudo`, which can reject password input unless it has a real terminal. Scarlet now runs those helpers inside a Pty4J-backed terminal in the installer dialog, so prompts stay inside Scarlet while still getting real PTY semantics.
- **Linux package install progress no longer hides terminal-style updates.** The progress dialog now streams raw output chunks instead of waiting for newline-terminated log lines or filtering terminal control sequences, so AUR/Pamac-style TTS voice installs can show the exact prompts and progress text emitted by the package manager.
- **Linux optional TTS installs now verify the selected engines.** The extra-voice installer now waits for the exact selected commands (for example `mimic`) to appear after the package manager runs, and terminal fallbacks print "Installation failed" with the real exit code instead of implying success when the package manager rejected or failed the install.
- **Linux desktop notification tests now catch broken sessions.** Scarlet now waits briefly for `notify-send` to report success, so the Settings test no longer says notifications are working when the command exists but fails because the notification daemon or DBus session is unavailable.
- **Verification auto-invite failures now tell the truth.** A VRChat 403 body of `"You're not a member?"` during invite means Scarlet's logged-in VRChat account is not a member of the configured group, not that the linked member is already invited. Scarlet now treats the placeholder auto-invite group as unset, falls back to the main configured VRChat group, downgrades expected invite rejections to WARN, and replies with setup-specific guidance for missing group membership, missing invite permission, missing group, or already-invited cases.
- **Suspicious-pronoun row coloring no longer overrides stronger advisories.** Watched group/avatar row colors now win over the amber pronoun hint, so higher-priority risk information stays visible.
- **Watched groups found through alternate VRChat credentials now appear correctly.** Scarlet was accidentally reusing the primary account's group list when merging an alternate account's lookup result, which could hide a valid watched-group advisory.
- **WorldBalancer avatar search uses Scarlet's endpoint.** The bundled WorldBalancer avatar-search provider now calls `/scarlet_search` instead of the old VRCX-compatible `/vrcx_search` route.
- **Linux TTS now plays on your actual output device.** With "TTS: Use default system audio device" enabled, Scarlet plays generated speech through an external player (`pw-play`, `paplay`, or `aplay`, auto-detected in that order) instead of the Java Sound API. Java Sound opens the ALSA hardware device directly and bypasses PipeWire/PulseAudio, so TTS never showed up in the system volume mixer and often played to the wrong (silent) device even when the `.wav` played fine manually. Playback now routes through the sound server, appears in volume control, and follows your selected output. Set the `SCARLET_TTS_PLAYER` environment variable (or `-Dscarlet.tts.player=...`) to force a specific player command. Windows and macOS fall back to the previous Java Sound path unchanged.
- **Verification group invite no longer fails with "Could not check your VRChat group membership status."** `getGroupMembership` mis-read VRChat's API: it treated only HTTP 404 as "not a member," but VRChat returns **403 "You're not a member"** when the queried user isn't in the group and 404 only when the group itself doesn't exist. Looking up a not-yet-member — i.e. every user being invited after verification — threw an error instead of reporting "not a member," so the auto-invite always failed. Non-member 403s are now handled correctly, which also fixes the same latent fault in the moderation and UI membership-status lookups.
- **Migration security hardening.** Several defences were added to the import path: the preferences XML is validated (with an XXE-hardened parser) so a bundle can only write Scarlet's own Preferences subtree and never inject arbitrary Java Preferences; per-entry, total-size and entry-count limits guard against zip bombs; restored files are written after deleting any existing target so a planted symlink can't redirect the write; and exported bundles, automatic backups, restored credential files, and the backup folder are now created owner-only (POSIX) with old pre-import backups auto-pruned, so decryptable secrets no longer accumulate world-readable. PINs are held in `char[]` and wiped after use.
- **Migration import is backed up and hardened.** Before importing, Scarlet now always creates a local pre-import backup bundle of the destination machine's current data and credentials, and aborts the import if that backup cannot be made. Import also refuses malformed bundles without `preferences.xml`, replaces Scarlet's existing Preferences subtree instead of leaving stale secure keys behind, flushes the imported Preferences before quitting, and skips shutdown saves after import so restored files remain intact. Migration and backup bundles contain directly decryptable secrets, so treat them like passwords and delete them when you no longer need them.
- **VRChat API preflight timeouts are quiet now.** The best-effort upstream version check no longer dumps a full DEBUG stack trace for normal network failures such as timeouts, DNS issues, refused connections, offline mode, or SSL/socket interruptions. Startup still reports that the bundled API is being used and the manual status dialog shows a short reason.
- **Missing optional Windows TTS voice registries are quiet now.** Scarlet still probes legacy SAPI and modern OneCore voice categories so it can find Microsoft Sam-era voices, per-user voices, Desktop voices, and Natural Voice adapters across Windows versions, but absent optional categories such as HKCU Speech or OneCore no longer print scary DEBUG stack traces.
- **Popups now match the dark main window.** The custom theme styled most components but never set `OptionPane`, `TextArea`, `TextPane`, `EditorPane`, or `List` colours, so message/confirm dialogs and the text blocks inside them fell back to FlatLaf's lighter default grey and clashed with the rest of the UI. These now use the same dark palette (dialog background matched to the main window base, text areas/lists to the input colour), so popups across the app look consistent.

## 0.4.18

Moderation-callout and settings-reliability release.

Highlights:

- **Condensed TTS callouts.** Join-time announcements for a user (watched user, watched group(s), new account, suspicious pronouns) are now combined into a single spoken line, and multiple watched groups are read together instead of only the first.
- **Smarter suspicious-pronoun detection.** Scarlet now recognizes real pronoun sets, including neopronouns, so fields like `she/her puppy` no longer false-flag, while genuinely abusive fields (self-harm directives, deny-listed terms, links/invites) are still caught.
- **Settings reliability.** Dropdown settings now display their saved value instead of resetting to the default, and Discord commands that change a setting update the desktop UI live.
- **Safer auto-invite default.** The verification auto-invite group now defaults to an obvious placeholder instead of a real group.

### Added

- `TTS: Announce suspicious pronouns` setting to toggle the spoken pronoun warning independently of the other announce switches.
- Built-in recognition of common pronoun sets and neopronouns, plus more comprehensive default `good_pronoun.json` / `bad_pronoun.json` lists.

### Changed

- Join-time TTS callouts are consolidated into one spoken line per user; multiple watched groups are listed together; vote-to-kick and watched-avatar phrasing trimmed.
- Suspicious-pronoun detection rewritten: a field containing a legitimate pronoun passes even with extra words, while self-harm/violence directives (including basic leetspeak), embedded deny-listed terms, and links/invites are flagged first.
- Verification auto-invite group default changed from a real group to a placeholder.
- Bumped Scarlet release / version metadata to `0.4.18`.

### Fixed

- Dropdown settings (e.g. mobile minimum severity) reset to their default when the Settings tab was rebuilt; they now show the saved value, and can no longer be silently overwritten by re-selecting the shown default.
- Settings changed via a Discord command now update the desktop Settings UI live — the change-listener registry no longer silently drops the UI updater (a `ConcurrentSkipListSet` comparator keyed only on priority treated distinct listeners as duplicates).

## 0.4.17-b3

Scarlet Companion (Android push notifications) release.

Highlights:

- **Scarlet Companion Android app.** Receive Scarlet alerts on your phone from anywhere in the world — no accounts, no third-party services. Pair by scanning a QR from Scarlet's settings.
- **Per-alert notification sounds and an in-app alert log.**

### Added

- Scarlet Companion Android app, with push notifications relayed through a lightweight Node.js server, per-alert-type sounds, a persistent in-app alert log, and per-type / minimum-severity notification controls.

### Changed

- Bumped Scarlet release / version metadata to `0.4.17-b3`.

### Fixed

- Stale notification channels after app updates, notifications not arriving when switching Wi-Fi → mobile data, silent pairing failure when no Firebase was configured, and watched group/user notifications being filtered out by the default severity threshold.

## 0.4.17-b2

TTS and packaging cleanup release.

Highlights:

- **Clearer mixed-character TTS alerts.** Scarlet now plays a bundled alert sound before a short spoken warning when a joined user's display name uses mixed scripts or lookalike characters, then lets TTS pronounce the best readable form without spelling out language labels.
- **Smaller desktop JAR.** RVC, the old lite/minimal package variants, and dormant staged browser automation code have been removed from the desktop build, while Android DAVE natives now stay in the Android artifact instead of the main desktop JAR.
- **Bundled startup security checks.** Security regression checks now live in the main Scarlet source tree and run during normal Scarlet startup instead of living under the test tree.
- **Fork update metadata.** `meta.json` now advertises `0.4.17-b2` for both release and build update checks.

### Added

- Bundled `tts/mixed-character-alert.wav` so mixed-character username alerts work from the packaged JAR without requiring a separate local audio file.
- Moved security regression checks into the main Scarlet source tree so the packaged JAR carries the checks with the application.

### Changed

- Mixed-character username TTS now uses a short alert phrase and best-effort readable pronunciation instead of announcing script names such as Cyrillic or Greek for each character.
- Mixed-character username normalization now favors Latin lookalike output when a display name is mostly readable as an English-style name, while still preserving full non-Latin names instead of forcing them through the lookalike path.
- The main desktop shade excludes Android and Termux DAVE native payloads; the Android classifier build still includes them automatically during normal Maven packaging.
- Bumped Scarlet release / version metadata to `0.4.17-b2` and pointed update / download release checks at the KozyBlake fork.

### Fixed

- Security regression checks are no longer stranded under `src/test`; they are bundled with Scarlet and executed as part of normal startup.

### Removed

- RVC voice-conversion code, bundled Python bridge assets, and RVC settings/UI wiring have been removed from Scarlet.
- The lite and minimal shaded JAR variants have been removed now that RVC is no longer shipped.
- Dormant staged browser/web automation sources, including the old NanoHTTPD/JCEF/Selenium experiment tree, have been removed.

## 0.4.17-b1_hotfix

Hotfix update for the first KozyBlake fork release.

Credit to ChanAurora for reporting both the help-desk report issue with custom-font usernames and the stale Scarlet version shown in generated reports.

Hotfix highlights:

- **Readable help-desk reports for stylized usernames.** Report URLs now use a normalized ASCII subject while preserving the exact VRChat display name in the report description so staff can identify the reported user.
- **Correct report versioning.** Report footers, Discord embed footers, UI labels, startup output, and packaged JAR metadata now report `KozyBlake/Scarlet 0.4.17-b1_hotfix`.
- **Fork update metadata.** `meta.json` now advertises `0.4.17-b1_hotfix` for both release and build update checks.
- **Lower-bandwidth update checks.** Scarlet reuses the same `meta.json` fetch for update and announcement checks and asks GitHub for `304 Not Modified` when nothing changed.
- **Security hardening for announcements.** Announcement link buttons are restricted to normal web links so `file://` paths and custom app protocols cannot be broadcast as clickable actions.
- **Security hardening for VRChat launch links.** Windows client launch now uses argument-list process creation and URL-encodes the VRChat location, preventing crafted locations from injecting extra launch arguments.
- **Announcement metadata guard.** GitHub now raises an immediate warning issue if `meta.json` announcement metadata is pushed by a GitHub actor outside the trusted announcer allowlist.
- **Fork branding polish.** Remaining plain `Scarlet` version labels, startup text, and log prefixes now use `KozyBlake/Scarlet` where user-facing fork identity matters.

Original 0.4.17-b1 highlights still included:

- **Scarlet on Android.** A new `scarlet-android` Maven module produces a standalone APK that tails VRChat logs from the official VRChat Android app over Wireless Debugging — no USB, no root, no terminal.
- **Group instance wizard.** A desktop UI for spinning up VRChat group instances with world / access / region / age-gate / avatar-performance prompts, and an option to launch straight into the new instance in VR or Desktop mode.
- **Discord warn + action-log channel.** A new `/discord-warn` command and a configurable action-log channel so warn / kick / ban events get a paper trail.
- **Faster, friendlier self-updates.** Hourly update polls, a manual *Help → Scarlet: Check for updates* entry, a fork-aware metadata URL, and a comparator that recognises `-b1` suffixes as iterations ahead of the bare release.

### Security Advisory

- **CVE pending / not assigned: announcement custom-protocol link handling.** During local abuse testing of the new GitHub-backed announcement popup, development builds accepted arbitrary URL schemes from `meta.json`. If a maintainer or compromised push-capable account published an announcement with a local file or custom protocol URL, and a user clicked the announcement's `Open link` button, Scarlet could hand that URI to the operating system through Java Desktop integration. Depending on the user's browser/OS/protocol-handler settings, this could invoke a local application or registered protocol handler. The fixed behavior allowlists only `http://` and `https://` announcement links; all other schemes are ignored defensively. The issue required control of the announcement metadata source plus user interaction, and no announcement link is opened automatically.
- **Announcement metadata guard.** Pushes that change `meta.json` on `main` are checked by GitHub Actions against a trusted announcer allowlist. Untrusted actors create a warning issue and fail the guard job. This warning path is triggered by GitHub's push event, not by raw-file polling, so it is not affected by CDN cache delay.

### Added

#### Scarlet on Android (new `scarlet-android/` module)

- New `scarlet-android/` Maven module producing a standalone APK (`scarlet-android-<ver>.apk`) that captures VRChat log output from the official VRChat Android app via an embedded ADB client over Wireless Debugging and writes a VRChat-formatted `output_log_<ts>.txt` that Scarlet's existing tailer consumes.
- One-time Wireless Debugging pairing flow in `MainActivity` / `AdbPairingService` using NSD discovery of `_adb-tls-pairing._tcp` plus a 6-digit pairing code — no USB, no root, no terminal commands.
- `ScarletLogService` foreground service that owns the `dadb` session + logcat tail and auto-reconnects when Wireless Debugging drops.
- `AndroidLogcatTail.rewrite(...)` translator that reformats `logcat -v year -s Unity:V VRCApplication:V` lines into the `yyyy.MM.dd HH:mm:ss Log - …` shape expected by `ScarletVRChatLogs.VRChatLogTail`.
- `scarlet.vrcAppData.dir` / `SCARLET_VRC_APPDATA_DIR` override in `VrcAppData.resolve(...)` so the Scarlet core tailer can be pointed at any directory — including the path where `scarlet-android` writes its `output_log_*.txt` — without patching `VrcAppData` on Android.

#### VRChat group instance creation

- Desktop UI wizard for creating VRChat group instances directly from Scarlet, with simple world / access / region / age-gate questions and an option to launch the new instance in the VRChat client in VR or Desktop mode.
- Instance-creation flow backed by the newer VRChat group instance APIs, with more complete support for recent VRChat API additions.
- Avatar performance gating in group instance creation, using the live VRChat API `minimumAvatarPerformance` field with Poor, Medium, and Good-or-better presets.

#### Discord

- `/vrchat-group` modern group API commands: audit-type discovery, member search, ban / invite / join-request lists, group posts, group announcements, and confirmed owner-level transfer actions.
- `/discord-warn` plus `/set-discord-action-log-channel` so Discord warn / kick / ban actions can be logged to a server-selected channel.
- Discord member join logging with invite-use detection when the bot has Manage Server permission. (Discord IP addresses remain unavailable because Discord does not expose them to bots.)

#### Self-update flow

- *Help → Scarlet: Check for updates* menu entry for an on-demand check that reports up-to-date / update-available / probe-failed results.
- `MiscUtils.compareScarletVersion` so a `-b1` suffix is treated as an iteration **newer** than the bare release — `0.4.17` → `0.4.17-b1` is now a recognised update.
- Public `Scarlet.checkUpdateNow()` entry point + `UpdateCheckResult` so the UI shares parsing and version-comparison logic with the periodic background check.

#### Misc

- Startup data-folder warning explaining that the `KozyBlake/Scarlet` data folder is separate, the legacy folder is left in place, and switching back to the original repo may require setup again.
- Hidden `popup-test` CLI dispatcher for dry-run popup testing without performing the confirmed action.

### Changed

- Bumped Scarlet release / version metadata to `0.4.17-b1_hotfix` and pointed update / download release checks at the KozyBlake fork.
- Periodic update poll runs **hourly** instead of every 3 hours.
- Update prompt rewording: "Hey, your release is *X*, there is a new release of *Y*. Open the download page?" (in both the auto-prompt and the new manual dialog).
- Default Scarlet data directories and user-agent branding moved from the legacy upstream owner name to `KozyBlake`, with a one-time opt-in prompt before copying data from the old default folder.
- Log categories and direct console log prefixes rebranded to `KozyBlake/Scarlet`.

### Fixed

- `meta.json` was missing a comma between its two fields, so the update probe failed JSON parsing every run; the file now parses cleanly.
- Fork update metadata URL now uses GitHub's raw endpoint, and a missing `meta.json` is downgraded to a warning instead of producing a startup error stack trace.
- Help-desk report subjects now normalize custom-font / stylized Unicode usernames into URL-safe ASCII, while the report description includes the exact original display name whenever normalization changes it.
- The custom-font username fix covers Latin-Extended stroke/bar glyphs like the `༒808 ȼɍᵾsȺđɇɍ༒` case (`tf_subject=808+crUsAder`), fullwidth and mathematical styled Latin, combining marks, CJK, Cyrillic, Greek, Hebrew, Arabic, Devanagari, and decorative Unicode flair.
- Version reporting now reads the packaged implementation version at runtime instead of relying on a compile-time-inlined constant, preventing report footers from showing an older build after partial recompiles.
- All primary packaged JAR variants now receive manifest implementation metadata so report/version text can resolve the correct build from the artifact itself.
- Report footer branding now uses `KozyBlake/Scarlet` instead of plain `Scarlet`.
- Background update / announcement polling now uses HTTP validators (`ETag` / `Last-Modified`), a short in-memory cache, and a shared 5-minute metadata cache key so unchanged `meta.json` checks do not repeatedly download the full file or get pinned to a stale GitHub CDN object for too long.
- Announcement checks now run every 10 minutes instead of hourly, while normal update checks remain hourly.
- Announcement link buttons now only open `http://` and `https://` URLs; local files and app/protocol links are ignored defensively.

### Removed

- `avatarrecovery` provider dropped from the default and configured avatar search providers.

## 0.4.16-b5
  - Added a third `minimal` edition built alongside full and lite; `mvn clean package` now produces `scarlet-0.4.16-b5-minimal.jar`
  - Added feature-gated command/subsystem wiring so the minimal edition disables built-in Discord kick/ban, watched-avatar management, scheduling/calendar, auxiliary webhooks, animated emoji generation, DAVE voice encryption, evidence submission, and RVC while keeping the core VRChat audit-log + Discord/TTS pipeline
  - Added Android/Termux support for Discord DAVE voice E2EE via `Platform.IS_ANDROID` / `Platform.IS_TERMUX`, `AndroidDaveJvmLibraryLoader`, and a local `DaveNativeBindings` override so the bundled Lavaplayer native loader is bypassed on Bionic
  - Added two Maven profiles for producing a Bionic-compatible native library: `-Pandroid-dave-native -Dscarlet.buildAndroidDaveNative=true` and `-Ptermux-local-dave -Dscarlet.localAndroidDaveNative=<path>`
  - Added runtime override knobs `scarlet.daveJvm.native` / `SCARLET_DAVE_JVM_NATIVE` and `scarlet.dave.native` / `SCARLET_DAVE_NATIVE`, plus `<Scarlet dir>/native/{termux,android}/libdave-jvm.so` / `libdave.so` search paths
  - Added "lite" edition built alongside the full edition - `mvn package` now produces both `scarlet-0.4.16-b5.jar` and `scarlet-0.4.16-b5-lite.jar`; the lite JAR has no RVC UI, no Python bridge, and skips the torch/rvc-python dependency install flow
  - Added compile-time feature flag `net.sybyline.scarlet.Features.RVC_ENABLED`, driven by the bundled `scarlet-features.properties` resource (stripped by the lite shade execution)
  - Added `tts_rvc_pitch` setting (range -24..+24 semitones) under Settings -> Text-to-Speech for tuning the RVC model's pitch offset when the TTS voice's fundamental doesn't match what the model was trained on
  - Added `--models-dir` flag to the RVC Python bridge so it finds models even when the bridge is extracted to a different subtree than the user's models directory
  - Added stem-contains matching for `.index` files so a model and its retrieval index are paired even when the filenames don't match exactly
  - Added automatic refresh of classpath-extracted bridge assets on startup so upgraded Python bridges take effect without requiring users to delete the AppData copy
  - Added per-install IPC authentication tokens so local IPC commands are no longer accepted unauthenticated by default
  - Added public-only outbound URL validation to block SSRF-style fetches against private, loopback, and local network targets across GIF conversion and import paths
  - Added strict GIF safety limits covering payload size, frame count, dimensions, and total pixel area to reduce memory/CPU denial-of-service risk
  - Added path sanitization and root-boundary checks for evidence exports so attachment-driven filenames cannot escape the configured evidence directory
  - Added explicit interaction ownership checks for pending moderation and instance-creation flows so one Discord user cannot hijack another user's in-progress controls
  - Added runtime permission re-checks on privileged Discord UI actions such as redact/unredact, tag editing, notes edits, watched-group imports, report-template changes, aux webhook changes, and moderation actions
  - Added safer interaction defaults by moving buttons/selects/modals onto explicit known-safe allowlists instead of framework-wide implicit allow
  - Added hardened encrypted-preferences migration with automatic legacy wrapper detection, backup export, and rewrapping under the current per-install secret
  - Added automatic recovery of older Discord and VRChat credentials by migrating legacy encrypted preference nodes to the current secret wrapper
  - Added startup backup warnings before secure-store initialization, including current and legacy data locations to copy before migration runs
  - Added startup diagnostics for secure preference initialization so stalls now log the current stage and worker-thread location instead of appearing frozen
  - Added deferred secure-store opening during VRChat startup so early splash progress remains visible before encrypted credential access begins
  - Added security regression checks covering encrypted preference round-trips, legacy wrapper migration, and public URL validation
  - Fixed RVC conversion failing with `_pickle.UnpicklingError: Weights only load failed` on PyTorch 2.6+ by transparently restoring `torch.load`'s `weights_only=False` default and allowlisting `fairseq.data.dictionary.Dictionary` via `torch.serialization.add_safe_globals`
  - Fixed RVC conversion failing with `Expected a JsonObject but was JsonPrimitive` when rvc-python progress prints leaked onto stdout - the bridge now routes library stdout to stderr during inference, and Java parses the last balanced JSON object rather than the whole buffer as a defence-in-depth measure
  - Fixed dependency-install wheel picker falling back to CPU on CUDA 13.0 because of a broken version comparison; now uses tuple comparison and correctly selects `cu124` for CUDA >= 12.4
  - Fixed unnecessary torch reinstall causing `WinError 5 - Access is denied` on Windows when the DLLs were already loaded; the install step is now skipped entirely when both `torch` and `torchaudio` are already present
  - Fixed post-install `_try_import` checks reporting packages as missing due to stale `importlib` finder / metadata caches; caches are now invalidated after every pip run
  - Fixed `torchcodec` incorrectly gating `rvc_compatible` in `--status` output; it is now in the optional package set (rvc-python 0.1.5 does not import it)
  - Fixed RVC-related settings appearing in the UI of the lite edition - they are now filtered out when `Features.RVC_ENABLED` is false
  - Fixed `EncryptedPrefs` decrypt flow so secure preferences actually decrypt in decrypt mode, and removed pointless base64-wrapping of plaintext before encryption
  - Fixed legacy secret-at-rest protection relying on a universal default fallback by moving to a persisted random per-install fallback secret
  - Fixed unauthenticated local IPC control on Windows named pipes and Unix sockets from being accepted without a secret by default
  - Fixed `/vrchat-animated-emoji from-url` and related remote-fetch paths accepting arbitrary internal/private targets
  - Fixed evidence file writes being vulnerable to path traversal through templated or attachment-derived names
  - Fixed privileged Discord interaction handlers trusting the UI layer too much by enforcing authorization again at execution time
  - Fixed pending moderation state and instance-creation state being reusable by other users who could trigger the same custom IDs
  - Fixed runtime dependency bootstrap trusting downloaded jars too easily by disabling it by default and validating remote checksums when enabled
  - Fixed startup appearing to freeze with no clue when secure preferences are slow or blocked by adding staged logging and splash progress around secure-store work
  - Fixed `libdave-jvm-master/CMakeLists.txt` referencing `src/main/cpp`, `boringssl`, `mlspp`, `libdave/cpp`, `cmake`, and `json-cpp` at the project root - the actual source tree lives under `natives/` - so the Android-aware root CMakeLists can now find the sources it needs when invoked by the `android-dave-native` Maven profile
  - Fixed `DaveLibraryLoader.handleMissingDependency()` on Termux now detecting glibc-only bundled binaries (`libc.so.6`) and surfacing an "incompatible native library" dialog instead of trying to run `sudo apt install libopus0`
  - Updated `vrchatapi-java` to `1.20.8-nightly.14`
  - Fixed the Settings UI occasionally painting card contents outside the scroll viewport during rapid resize/scroll, which could draw controls over the tab strip, search row, or footer
  - Fixed `java.io.IOException: The handle is invalid` being thrown ten times a second from `Scarlet.spin()` on Windows when the user launched Scarlet without an attached console (double-clicked JAR via `javaw.exe`, Windows shortcut, Task Scheduler, detached launch) - the CLI reader now detects the invalid stdin handle on first failure, logs it once at INFO, and skips further `System.in.available()` polling for the session
  - Fixed `ErrorResponseException: -1: java.io.InterruptedIOException` being logged at ERROR when closing Scarlet while JDA still had in-flight command-registration REST calls (triggered by `updateCommandList()` after a version change) - installed a custom `RestAction.setDefaultFailure` handler in `ScarletDiscordJDA` that routes `InterruptedIOException` down to DEBUG and delegates all other failures to JDA's original default handler
  - Fixed inverted `awaitShutdown` condition in `ScarletDiscordJDA.close()` - the code was force-shutting-down JDA only after graceful shutdown had already succeeded (no-op) and doing nothing when the 10s timeout elapsed (leaving pending requests hanging); now correctly force-shuts-down on timeout and preserves the thread interrupt status on `InterruptedException`
  - Fixed RVC, TTS, and xdg-utils dependency-installer dialogs having their Yes/No buttons clipped off the bottom of the screen on Windows at elevated display scale (125%/150%/200%)
  - Fixed RVC dependency installation failing with "pip reported success installing rvc-python but the package is still not importable" on fresh Python 3.10/3.11 installs by adding compatibility shims for `collections.abc`, Python 3.11 dataclass defaults, and better import-error surfacing in the bridge
  - Fixed `_try_import` silently discarding exception details - the bridge now records the actual exception into a process-wide `_IMPORT_ERRORS` map and surfaces broken imports in `--status`
  - Changed `TtsService` RVC accessors (`isRvcAvailable`, `getRvcStatus`, `getRvcModelsDir`, `getRvcModels`, `setRvcConfig`) to null-safe so a null `RvcService` in the lite edition is handled gracefully
  - Changed `RvcService.getResourcePath()` to prefer a fresh classpath extraction over the AppData cache, so bundled bridge upgrades take effect on the next launch
  - Indefinitely postponed: Support for multiple groups
  - Pending: Staff & Instance Analysis, (live infographic?)
  - Pending: Limited Google Drive interoperability
  - Pending: Distinct Server and Client modes
## 0.4.16-b4
  - Added built-in `/discord-kick` and `/discord-ban` Discord slash commands using the native Discord member picker
  - Added optional reason parameter to `/discord-kick` and `/discord-ban`
  - Added DM notification sent to the target user before a kick or ban is carried out
  - Added one-time opt-in prompt on first startup asking whether to enable the built-in Discord kick/ban commands
  - Added `discord_kick_ban_enabled` toggle under Settings Ã¢â€ â€™ Discord
  - Added ephemeral response when `/discord-kick` or `/discord-ban` is used while the feature is disabled, directing the user to Settings
  - Added in-app CLI panel as a dedicated "CLI" tab in the Scarlet UI, with a terminal-style read-only output area and a command input field
  - Added `rawCommand` output routing so CLI results appear in the in-app panel as well as the system log
  - Added "Run CLI command" entry under Settings Ã¢â€ â€™ CLI for running commands via a popup dialog
  - Added startup banner to the console showing the Scarlet version and a hint to type `help`
  - Fixed CLI `help` command output not being printed to the console
  - Improved logging for Discord kick and ban actions (actor, target, guild, reason all recorded at INFO level)

## 0.4.12
  - Added User Spawn Prop extended event
  - Added notes field for watched entities and groups
  - Added autocomplete for watched entity and group ids
  - Added filter to narrow potential avatars based on author id
  - Updated JDA to 5.6.1 (was 5.2.1)
  - Fixed custom Emojis not being properly detected
  - Fixed some text not being sanitized for Markdown
  - Fixed some events not firing on player joins
  - Fixed watched entities not loading or importing
  - Fixed watched users commands failing and having incorrect links

## 0.4.12-rc8
  - Fixed error in output for banning or unbanning multiple users with the appropriate Discord command
  - Fixed error when custom moderation tags is exactly a multiple of 25

## 0.4.12-rc7
  - Added User Spawn Emoji and Watched Moderation extended events
  - Added Ban User and Unban User buttons to several event embeds (User Switch Avatar, User Spawn Pedestal/Sticker/Print/Emoji)
  - Added Watched Users and Watched Avatars
  - Added user current avatar name to Instance UI
  - Changed maxium number of custom moderation tags to 125 (was 25)
  - Fixed watched entity imports via Discord command using the incorrect option name
  - Fixed mislabeled Discord command descriptions
  - Fixed silenced watched groups overriding TTS messages for lower priority groups

## 0.4.12-rc6
  - Added links to the Scarlet VRChat Group
  - Added Group Invites created to Moderation Summary
  - Added option to skip confirmation to send a group invite to players via Instance UI
  - Added deep equality check to avoid editing Discord commands with identical content
  - Changed the `Edit tags` button on moderation events to auto-populate currently assigned tags
  - Fixed custom moderation tags with long descriptions causing some Discord interactions to fail
  - Fixed Instance UI failing to send group invites
  - Fixed instability caused by unexpected/unknown API enum values failing deserialization

## 0.4.12-rc5
  - Added `tag-immediately` option to `vrchat-user-ban` Discord slash command, allowing user to specify tags and description before banning
  - Added user pronouns and status description to moderation event embed
  - Added Invite to Group button to Instance UI
  - Added API call rate limit to Instance UI
  - Changed Instance UI to skip updating during preamble
  - Fixed `permissions` autocomplete suggestions missing for Discord interactions
  - Fixed VRChat Help Desk autofill links missing most information
  - Fixed sticker images not showing up in the User Spawn Sticker extended event embed

## 0.4.12-rc4
  - Added Outstanding Moderation and Action Failure extended events
  - Added `outstanding-moderation`, `submit-evidence`, `vrchat-user-ban-multi`, and `vrchat-user-unban-multi` Discord slash commands
  - Added optional extra filter step to narrow down potential avatars from User Switch Avatar extended event
  - Removed unloaded libraries from distribution
  - Changed api cache default max age
  - Fixed bugs with the intended ephemerality of Discord responses being ignored
  - Fixed bugs with ipc-piped commands not executing

## 0.4.12-rc3
  - Added setting to configure which VRCX-compatible avatar search providers to use
  - Added cli commands via named pipe
  - Added report links to various events and search results
  - Added button to view potential avatar matches for easier manual correlation
  - Changed VRChat Client launching to use URI on non-Windows platforms

## 0.4.12-rc2
  - Added avatar thumbnail to User Switch Avatar extended event (not foolproof, can be default/previous avatar image)
  - Updated VRChat Help Desk autofill links to account for recent changes
  - Fixed bugs with bundled bans/kicks

## 0.4.12-rc1
  - Added setting to launch a new VRChat Client instance into newly created group instances
  - Added option to assign/remove a VRChat Group Role when adding/removing staff from lists
  - Added Discord subcommands `add-role` and `remove-role` to `vrchat-group`
  - Added Discord message command `fix-audit-thread` to be used when moderation event messages fail to populate
  - Changed Discord command `query-target-history` to redact secret staff names in event description
  - Fixed typo in event log messages description header

## 0.4.11
  - Added User Spawn Print extended event
  - Added Bundle ID to User Switch Avatar extended event (when available)
  - Added user age verification status to Instance UI
  - Added title to runner

## 0.4.11-rc11
  - Added User Spawn Pedestal, User Spawn Sticker, and Suggested Moderation extended events
  - Added autocomplete to VRChat User and VRChat World options for Discord commands
  - Changed default Discord commands permissions to `USE_APPLICATION_COMMANDS`

## 0.4.11-rc10
  - Added ban/unban buttons to Vote-to-Kick log entries
  - Added snapshot repository to dependency downloader
  - Changed periodic report to include group joins, leaves, and kicks
  - Fixed crash loading permissions data
  - Fixed bug with empty pagination

## 0.4.11-rc9
  - Internal overhaul of all Discord commands and interactions
  - Added the `/scarlet-discord-permissions` Discord command
  - Added the `/vrchat-search` Discord command
  - Added file lock so multiple instances of Scarlet do not run for the same group at the same time on a given system
  - Added Discord commands (`vrchat-animated-emoji`) that convert gifs into spritesheets for VRChat Gallery animated emoji
  - Removed the `/scarlet-permissions` Discord command (old configuration will not be lost if you update)
  - Changed the `/watched-groups` Discord command to consolidate subcommands
  - Changed Discord command default permissions to requiring only either `MODERATE_MEMBERS` or `MANAGE_SERVER`, depending on the sensitivity of the command
  - Fixed selector in settings tab for evidence root not having any effect

## 0.4.11-rc8
  - Added functionality to paginated Discord command responses
  - Changed WorldBalancer domain from `avatarwb.worldbalancer.duia.us` to `avatar.worldbalancer.com`
  - Fixed `/(secret-)staff-list list` command sometimes failing

## 0.4.11-rc7
  - Added input sanitization/resolution for many Discord commands to accept many intuitive forms for groups, users, worlds, etc. (VRChat Web links, group short codes, etc.)
  - Added `/vrchat-group` Discord command, with `create-instance` and `close-instance` subcommands
  - Added customizability for JVM options via `./java.options` file
  - Removed attachment containing user state to moderation events (will rework to improve usability)

## 0.4.11-rc6
  - Fixed TTS not outputting to Discord voice channel
  - Fixed versions being both missing and in reverse order in `/server-restart` Discord command autocomplete

## 0.4.11-rc5
  - Added tentative support for tailing the VRChat client logs on Linux
  - Added setting to modify the audit polling interval from the canonical 60 seconds
  - Fixed some embeds not having linked titles
  - Fixed secret channels not being set

## 0.4.11-rc4
  - Fixed duplicate avatar ids being logged
  - Fixed embed field having too many characters
  - Fixed misleading output from `run.bat`
  - Fixed an infinite loop of failure `run.bat`

## 0.4.11-rc3
  - Added update option to `/server-restart` Discord command
  - Fixed duplicate alternate field in AvatarSearch.VrcxAvatar

## 0.4.11-rc2
  - Added naive in-memory cache for avatar searches
  - Fixed IllegalArgumentException in TTSService
  - Fixed NullPointerException in staff lists
  - Fixed StackOverflowError in ScarletData

## 0.4.11-rc1
  - Added extended events: User Join, User Leave, User Switch Avatar, and Instance Monitor
  - Added avatar search support (only exposed internally for now)
  - Added support for auto restarting (when runner is used)
  - Added secret staff list: events actored by these users can be diverted to other channels and are not visible to normal staff under most conditions
  - Changed handling of Vote-to-Kick Initiated extended event to properly handle initiator name when applicable
  - Changed Moderation Report extended event to include totals and optionally omit staff without activity
  - Removed the `associate-ids` Discord command in deference to using the staff commands
  - Fixed some extended events not being sent to auxiliary webhooks
  - Fixed Markdown escaping for display names in multiple places

## 0.4.10
  - Added UI scaling
  - Added `joins` field to moderation summary
  - Changed templating for VRChat Help Desk autofill to optionally append footer
  - Changed TTS to replace some special Unicode characters before generating audio
  - Added primitive input sanitization of the Group ID
  - Fixed some users having negative account age in Instance UI
  - Fixed AcctAge column of Instance UI comparing by textual instead of numerical value

## 0.4.10-rc5
  - Added daily moderation summary with configurable time-of-day to generate
  - Added templating for VRChat Help Desk autofill
  - Added TTS announcements for Votes-to-Kick Initiated
  - Added tentative search of the system path for different PowerShell executable names

## 0.4.10-rc4
  - Added location to extended event embeds
  - Changed evidence submission to not require a direct reply, but instead to just be in the same thread

## 0.4.10-rc3
  - Added settings for enabling and selecting the evidence submission folder
  - Added listing auxiliary webhooks to `config-info`
  - Fixed some settings not getting created as default if they didn't exist

## 0.4.10-rc2
  - Fixed Email OTP (again)
  - Added Instance Inactive extended event
  - Staff mode now properly ignores audit log polling and related systems
  - Fixed app hanging on startup if no VRChat logs were found
  - Fixed log parsing for Vote-to-Kick Initiated
  - Fixed data not getting saved
  - Fixed the `/set-audit-ex-channel` having a typo in one of its options

## 0.4.10-rc1
  - Added staff list command `/staff-list`
  - Added extended events: Staff Join, Staff Leave, and Vote-to-Kick Initiated
  - Added audit event bundling: if a User Ban results in a Instance Kick, then the latter is logged with the former by default
  - Changed the command suggestions to display more human-friendly names over internal identifiers
  - Fixed Discord roles being mistakenly formatted like Discord users
  - Fixed moderation events initiated with commands or buttons not properly referencing the initiating user

## 0.4.9
  - Added ability to redact audit events: they do not count in the running sum of moderation events for embeds
  - Fixed autocomplete for the `scarlet-permission` Discord command not working
  - Fixed manual command update button not working

## 0.4.9-rc3
  - Added ability to revert instance UI to default ordering by right clicking header
  - Changed Discord command `set-permission-role` to `scarlet-permission` and added support for multiple roles
  - Fixed bug (#9) that tried to set a Discord embed field value to more than 1024 characters

## 0.4.9-rc2
  - Added application version change detection
  - Changed Discord bot to only send command list if version changes
  - Changed instance UI to only sort once the log tailing/parsing has caught up 
  - Changed watched groups sort order to prioritize `priority` instead of the flags `critical` and `silent`
  - Fixed NPE when emitting aux webhooks for certain events
  - Fixed watched groups added via Discord commands missing the `id` field, causing them to magically disappear

## 0.4.9-rc1
  - Fixed bug: Discord bot responds properly to the `set-audit-aux-webhooks` command
  - Updated dependency: com.github.vrchatapi:vrchatapi-java:1.18.9 to version 1.19.1

## 0.4.8
  - Added ability to silence TTS messages for particular groups
  - Added ability to import watched groups from UI
  - Fixed bug: removed references to restricted/internal api
  - Fixed regression: switched emailotp and totp auth flows

## 0.4.8-rc1
  - Added experimental "staff mode" (discord integration disabled, data like watched groups must be synced manually)
  - Added ability to ban and unban VRChat users with desktop UI, Discord buttons, and Discord commands
  - Added ability to disable pings on Discord for moderation audit events
  - Added priority system to groups
  - Added default sorting for instance UI
  - Added colored text based on group watch type for instance UI
  - Added ability to forward audit event messages to other Discord servers via webhooks
  - Added AuditID to autopopulation of description field for VRChat Help Desk Links
  - Added support for Email OTP
  - Fixed TTS subprocess hang if selected TTS voice isn't installed on the system
  - Fixed regression: implicit class loading from main class happened before dependencies were downloaded

## 0.4.7
  - Fixed regression: TOTP codes incorrectly reporting as invalid

## 0.4.6
  - Added splash screen
  - Added update check popup
  - Added automatic disabling log parser if VRChat client not present
  - Fixed auth refresh not triggering
  - Fixed setting TTS voice not working or persisting
  - Changed UI theming

## 0.4.6-rc1
  - Added list of users in current instance to the UI
  - Added various links to the UI
  - Added ability for group to autopopulate email field for VRChat Help Desk Links
  - Fixed watched groups not being properly detected

## 0.4.5
  - Added caching layer for some VRChat API requests
  - Added some autocomplete for the `watched-group` command
  - Added initial text `Unclaimed` to new moderation events
  - Fixed missing reprompt on invalid credentials
  - Fixed incorrect gateway intent in instructions

## 0.4.5-rc4
  - Added ability to specify where Scarlet stores data
  - Added ability to select a TTS voice
  - Added Discord commands to configure watched groups
  - Added Discord command to export Scarlet logs
  - Added dummy UI for ease of exiting the application
  - Added default command permissions for Discord commands
  - Changed log name format for JDA classes

## 0.4.5-rc1
  - Fixed NPE when checking if a joining player has watched groups

## 0.4.4
  - Fixed importing legacy CSV list of watched groups (Excel format, instead of RFC4180)

## 0.4.3
  - Added step to installation instructions to make discord bot private
  - Added newer version check
  - Added more build automation
  - Fixed discord bot responding to interactions from servers other than the server specified in configuration
  - Fixed discord bot sometimes not automatically rejoining voice channel
  - Fixed some errors not printing to log file
  - Changed link to download a JDK from potentially confusing Github link to Adoptium website proper

## 0.4.2
  - Added important step to installation instructions
  - Added `pause` line to runner
  - Removed input prompt for Discord audio channel snowflake
  - Fixed bug in dependency downloader not properly unescaping characters
  - Changed Java version check to warn instead of terminating application

## 0.4.1
  - Added logger thread and log files
  - Added importing watched groups legacy CSV via attachment submissions
  - Fixed bug where the process could hang upon quitting
  - Fixed typos and missing comments
  - Changed some logging levels
  - Changed handling of tags for importing watched groups legacy CSV

## 0.4.0
  - Added installation and usage instructions to README.md
  - Added CHANGELOG.md
  - Added SETTINGS.md
  - Added automation for building a zip
  - Added runtime check for JVM's Java version
  - Added ability to automatically generate TOTP code from secret
  - Added link and message reference to group audit event `group.instance.close`
  - Added TTSService to announce events in a Discord voice channel
  - Added VRChat log tailing/parsing
  - Added Watched Groups, which generate TTS announcements when members' joining is detected
  - Removed unused code and resources from automatic dependency downloader
  - Renamed `submit-evidence` Discord message command to `submit-attachments`
  - Fixed audit event embed color overrides being ignored
  - Fixed typos

## 0.3.3
  - Fixed escaping for VRChat Help Desk autofill link generation
  -
