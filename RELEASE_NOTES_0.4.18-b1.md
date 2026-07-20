# Scarlet 0.4.18-b1 Release Notes

Scarlet 0.4.18-b1 makes the moderation feed dramatically faster, teaches avatar statuses to work without VRChat launch options, overhauls the export experience, and cleans up a batch of long-standing rough edges.

## What's New

### Moderation actions post in seconds, not minutes

Scarlet previously waited out the full audit polling interval (default 60s) before a moderation action appeared in the moderators' Discord channel — and busy nights made it feel even slower. 0.4.18-b1 introduces **burst polling**:

- When a moderation action is initiated through Scarlet (Discord command, UI, or the bulk-ban queue), the audit log is polled every **10 seconds for up to 2 minutes**, so the Discord thread posts as soon as VRChat's audit endpoint exposes the entry.
- **Flurry detection**: moderation comes in waves. When *any* warn/kick/ban is observed — including actions taken purely in-game — the burst window is re-armed, so the rest of the wave posts within seconds instead of a poll cycle behind.
- Any failed audit query (including a 429 rate limit) instantly drops back to the normal interval, and idle API usage is unchanged.

Audit queries also now re-cover a **5-minute overlap window** every poll. VRChat can ingest audit entries out of order; previously a late-ingested entry could fall permanently behind the query cursor and never post. With the overlap plus per-entry dedupe, every action posts exactly once — even during simultaneous multi-moderator waves on a laggy night.

### Avatar statuses without launch options

Avatar bundle/status info used to require launching VRChat with the `[API]` debug-logging launch options; without them, statuses silently stayed empty. Scarlet now reconstructs the data itself:

1. **Name lookup** — the avatar name (always logged) is matched against the avatar search databases for candidate IDs.
2. **Image verification** — candidates are compared against the user's current-avatar image file, which uniquely identifies the *true* avatar even among dozens of same-named clones (up to 40 candidates scanned, plus avtrDB's search-by-image).
3. **Full stats via the VRChat API** — the confirmed avatar's bundle file and analysis are fetched, producing the same stats as the log-based path.
4. **Private avatars** — fall back to a database record's stored performance rating, nameplate-style.

With launch options enabled, the exact log-based path still wins; the fallback acts as a 20-second backstop for anything the log path fails to resolve. Players already present when Scarlet starts mid-session are swept automatically after catch-up, with requests staggered to respect the search providers' rate limits.

When data genuinely can't be pulled (profile-picture overrides, unindexed private avatars), the dialog now tells you the actual reason — and shows the exact launch options in a selectable box with a **Copy launch options** button.

### A readable avatar stats dialog

The "View" dialog opens compact — avatar name, performance rating, file size, uncompressed size — with a **Show all details** toggle for the full statistics list. Values are human-formatted: byte counts as MB, timestamps in your local time, `145,231` instead of `145231`, bounds as `2.00 × 1.65 × 2.30`. The dialog hugs its content when collapsed and grows when expanded.

### Avatar search providers: managed, expanded, hardened

- **Manage dialog**: Settings now has **Avatar search providers -> Manage...** — every known provider as a checkbox, an add-custom-URL field, and a Restore defaults button. Saving rescans only the players whose avatars still lack data; already-resolved players are skipped, and per-provider caching means unchanged providers answer from memory.
- **Three new databases**: avtr.zip, PAW (Puppy's Avatar World), and KitsuneDB join avtrDB, NSVR, VRCDB, and WorldBalancer — seven providers total. Only providers that honor creators' removal/blacklist requests are included.
- **Fixed endpoints**: NSVR moved hosts and WorldBalancer's old route was defunct — both work again.
- **Format tolerance**: providers' native response shapes (wrapper objects, nested author records) are now accepted, and malformed responses trigger a quiet 5-minute backoff instead of a stack trace per search.

### Export overhaul

- **Live progress**: exporting a migration bundle shows an Explorer-style dialog — phase, current file, "N of M files", byte totals — with a working **Cancel** that never leaves a half-written bundle behind.
- **Huge-log guard**: when logs+caches exceed 1 GiB (20 GB+ log folders exist in the wild), export offers to clear them first, keeping the current session's log, and reports the space freed.
- **VRChat check**: exporting while VRChat is running warns with the process ID(s) shown and offers Check again / Export anyway / Cancel.

### UI consistency pass

The dark theme now covers every stock component type — scroll panes, viewports, checkbox/radio labels, password fields, spinners, sliders, progress bars, trees, and more — eliminating the mismatched light-grey patches. The hand-styled parts of the main window were moved onto one shared palette so shades can never drift apart again.

## Fixes

- **Cache cleanup no longer tries to delete the live log** (the "file in use" warnings on every cleanup). The live-log guard now matches by canonical path and the per-session-unique file name instead of raw path-string comparison.
- **Out-of-order audit entries are never silently lost** (see overlap window above).
- **Stale avatar info** is cleared from a player's row immediately when they switch avatars.
- **Windows display-scaling UI corruption** (overlapping buttons/tables at 125%/150% scale) is fixed by disabling Java2D's display scaling at startup and letting FlatLaf derive the scale from the system font.

## Upgrade notes

- No settings migration needed. Users with a custom avatar-search provider list keep it; open **Avatar search providers -> Manage...** and press **Restore defaults** to adopt the new seven-provider list.
- `audit_polling_interval` still controls the idle poll rate (10–300s); burst polling operates within it automatically.
- For exact avatar statuses (including private avatars), the VRChat API-logging launch options remain the gold standard — Scarlet's launcher applies them automatically, and the fallback covers everything else.
