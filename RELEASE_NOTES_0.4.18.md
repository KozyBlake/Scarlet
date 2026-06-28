# Scarlet 0.4.18 Release Notes

Scarlet 0.4.18 brings desktop notifications, one-file PIN-encrypted migration between machines, a smarter main window, significant Linux quality-of-life improvements, and dozens of bug fixes.

## What's New

### Desktop notifications (toasts)

Scarlet can now raise native desktop notifications for advisories — no PowerShell required. Windows uses the system-tray balloon (surfaced as Action Center toasts in Windows 10/11); Linux uses `notify-send`; macOS uses `osascript`.

A new **Settings -> Desktop Notifications** section adds:
- A master on/off toggle (default off)
- Independent per-category toggles: watched users, groups, avatars, votes-to-kick, moderation, staff joins/leaves, new players, mixed-character names, suspicious pronouns
- A **Send desktop test notification** button
- On Linux, if `notify-send` is missing, the test button and first failed live toast offer to install the right libnotify package

Toasts fire only for live joins, never when an instance first loads, and the tray icon is created lazily on first use.

### One-file migration between machines

A new **Settings -> Backup & Migration** section lets you move your entire Scarlet identity to another PC (or OS) in one step.

- **Export bundle** writes a single PIN-encrypted `.zip` containing Scarlet's data folder plus Java Preferences credentials exported as OS-independent XML — so registry-backed Windows credentials import cleanly on Linux/macOS. The PIN (4+ characters) encrypts the bundle with AES-256-GCM (PBKDF2-SHA256), and the GCM tag authenticates the bundle so it cannot be silently altered. A lost PIN cannot be recovered.
- **Import bundle** lets you restore data/config files, secure credentials, or both. Import restores into the correct data and Preferences locations for the destination machine, then quits Scarlet so the old in-memory session cannot overwrite the imported files.
- **Move-out option**: An "I'm moving to another computer" checkbox in the export dialog. When set, Scarlet offers to wipe the local data folder and credentials after the bundle is written. The destructive button has a ~10-second countdown before it can be pressed.

The migration path is hardened against zip bombs (per-entry, total-size, and entry-count limits), XXE attacks (XML parsed with a locked-down parser), symlink redirection (targets deleted before write), and world-readable secrets (owner-only POSIX permissions).

### Main window decluttering

- **Right-click column visibility.** Right-click the player-list header to show or hide columns — the same list as Edit -> Columns, now where you'd expect it. Your choices are still remembered between launches.
- **View menu.** A new **View** menu lets you hide the **CLI** tab if you don't use it (`ui_show_cli_tab`).

### Per-category advisory toggles

A new **Settings -> Advisories** section gives independent control over each advisory category: watched users, watched groups, watched avatars, new players, mixed-character names, votes-to-kick, and suspicious pronouns. Turning a category off hides its Instance-table advisory **and** suppresses that category's TTS callout.

Advisory text has also been slimmed: multiple advisories use a readable inline separator instead of newline boxes, suspicious-pronoun advisories collapse to a short label, and watched avatars can populate the Advisory column when detected.

### Pronoun toggles are now visible in Settings

"TTS: Announce suspicious pronouns" and the new "TTS: Flag suspicious pronouns" master toggle both appear in the Text-to-Speech settings section — no more editing the settings file by hand. They're hidden in editions built without the pronoun feature.

### Unlink VRChat accounts

- **Self-service:** `/unlink-vrchat-account` lets any member remove their own Discord-to-VRChat link, handy for re-testing verification.
- **Staff:** `/unlink-vrchat-account-for` (requires Moderate Members) unlinks another member by Discord user and/or VRChat user.

Both commands only clear the link record — they don't change Discord roles or VRChat group membership.

### Linux improvements

- **Package installs now work.** The TTS / xdg-utils install prompts used `which` to detect terminals and then launched an external emulator — neither worked on modern minimal distros. Scarlet now uses `pkexec` for privileged installs (a graphical polkit prompt, no terminal required) and an embedded Pty4J-backed terminal inside its own installer dialog for everything else.
- **User-space managers stay as your user.** Homebrew, Nix, AUR helpers (`yay`, `paru`, `pamac`) run as the current user with the same live-output progress dialog, avoiding root-owned caches or outright failures.
- **Real PTY for AUR passwords.** Helpers that spawn `sudo` get a real pseudo-terminal so password prompts work inside Scarlet instead of failing silently.
- **Live install progress.** The dialog streams raw output chunks in real time instead of waiting for newline-terminated log lines, so AUR/Pamac-style voice installs show the exact prompts and progress text.
- **Engine verification.** The extra-voice installer waits for the selected commands (e.g. `mimic`) to appear after the package manager runs, reporting actual failure instead of silent success.
- **Guided audio-player install.** If local TTS playback is enabled but none of `pw-play`/`paplay`/`aplay` is present, Scarlet offers to install `alsa-utils` (`aplay`) via your package manager. Until one is installed, playback falls back to direct ALSA output.
- **More TTS voices, installed by choice.** Linux TTS now discovers Flite, Mimic, Pico/`pico2wave`, Festival/`text2wave`, eSpeak NG, and legacy eSpeak. Voice choices are shown as `engine/voice` (e.g. `flite/slt`), and a **Settings -> Text-to-Speech -> Install Linux TTS voices** button lets you explicitly install extra engines.
- **TTS plays on your actual output device.** With "TTS: Use default system audio device" enabled, Scarlet plays through an external player (`pw-play`, `paplay`, or `aplay`) instead of Java Sound's direct ALSA hardware access — so TTS now appears in the system volume mixer and follows your selected output.

### Restart now relaunches Scarlet itself

`/server-restart restart-now` and the new CLI `reboot` / `restart` commands start a fresh Scarlet process from the current Java command line instead of only exiting with a restart code. The relaunch uses the current JAR path or classpath, inherits the terminal streams, and does not depend on the JAR filename.

### Slimmer advisory text

Multiple advisories in the Instance table now use a readable inline separator instead of newline boxes, suspicious-pronoun advisories collapse to "Suspicious pronouns", and watched avatars can populate the Advisory column when detected. Verification auto-invite no longer pre-checks group membership — eligibility is decided at link time and invite failures are classified after VRChat responds.

## Fixed

- **Repeated TTS callouts and notifications (Linux).** Scarlet no longer re-reads the current VRChat session log from byte 0 when Proton bumps the log modified time without appending readable bytes, so old joins are not re-announced as live events.
- **Missing instance players after restarting VRChat.** When VRChat starts a new session log while Scarlet keeps running, Scarlet now resets the per-log live state and flushes queued catch-up rows so players already in the new instance appear in the table immediately after catch-up.
- **Linux package installs actually run now.** Using `which` to detect terminals failed on distros without it; Scarlet now uses `pkexec` first and an embedded PTY terminal as fallback.
- **Linux user-space managers no longer run as root.** Only managers marked as requiring sudo get `pkexec`; Homebrew, Nix, AUR helpers run as the current user.
- **AUR password prompts now work.** Helpers (`yay`, `paru`, `pamac`) that spawn `sudo` are run inside a Pty4J-backed real terminal so password input is accepted.
- **Linux install progress shows terminal output.** Raw output chunks are streamed instead of waiting for newlines or filtering control sequences.
- **Linux optional TTS installs actually verify.** The installer waits for the selected commands to appear and reports the real exit code on failure.
- **Desktop notification test catches broken sessions.** Scarlet waits for `notify-send` to report success, so the Settings test no longer says notifications work when the daemon is unavailable.
- **Verification auto-invite failures now tell the truth.** VRChat returns 403 "You're not a member?" when Scarlet's account isn't in the group, not when the target is already invited — now handled correctly with setup-specific guidance for each failure case.
- **Verification group invite no longer errors on "Could not check your VRChat group membership status."** Non-member 403s are now handled correctly instead of throwing, which also fixes the same latent fault in moderation and UI membership lookups.
- **Suspicious-pronoun row coloring no longer overrides stronger advisories.** Watched group/avatar row colors win over the amber pronoun hint.
- **Watched groups from alternate credentials appear correctly.** Primary account group list no longer accidentally merged with alternate results, which could hide valid advisories.
- **WorldBalancer avatar search uses Scarlet's endpoint.** The bundled provider now calls `/scarlet_search` instead of the old VRCX route.
- **Linux TTS plays on the right device.** External player routing (`pw-play`/`paplay`/`aplay`) replaces direct ALSA hardware access so TTS follows the system volume mixer.
- **VRChat API preflight timeouts log quietly.** Best-effort upstream version checks no longer dump full DEBUG stack traces for normal network failures.
- **Missing Windows TTS voice registries log quietly.** Absent optional Speech categories no longer print scary stack traces.
- **Popups now match the dark main window.** Dialog backgrounds, text areas, and lists use the same dark palette as the rest of the UI.
- **Migration hardening.** XML validation with an XXE-hardened parser, zip-bomb limits, owner-only file permissions, symlink-safe writes, and PINs held in `char[]` and wiped after use.
- **Migration import is backed up.** A local pre-import backup bundle is always created before importing, and the import is aborted if the backup cannot be made. Import refuses malformed bundles without `preferences.xml`, flushes imported Preferences before quitting, and skips shutdown saves so restored files stay intact.

---

## Files in this release

| File | Description |
|------|-------------|
| `scarlet-0.4.18.jar` | Scarlet desktop application |
| `scarlet-0.4.18-android.jar` | Scarlet Android application |

The **Scarlet Companion** Android app is unchanged since `0.4.17-b3`; keep using the APK from that release.

## Upgrading

Drop in the new desktop JAR as usual. No settings migration is required. If you plan to use the new **Backup & Migration** feature, note that bundles are PIN-encrypted — choose a PIN you won't forget, as lost PINs cannot be recovered.

Linux users: Scarlet now discovers multiple TTS engines on your system. If you use TTS and don't have one installed, you'll be prompted to install one automatically. You can install additional engines later via **Settings -> Text-to-Speech -> Install Linux TTS voices**.
