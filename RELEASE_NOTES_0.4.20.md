# Scarlet 0.4.20

A feature release focused on **spotting the right people faster** and **reacting to new instances faster**, plus a readability pass on the player-list colours.

> Version header is provisional (0.4.20) — rename if you'd rather cut this as something else.

## Post-release compatibility and moderation-tag updates

- **VRChat avatar-tag compatibility.** Scarlet now accepts the current VRChat response where `presence.currentAvatarTags` is an array, even though the bundled `vrchatapi-java` 1.21.0 SDK still expects a string. This prevents the current-user response from failing to deserialize while leaving the correctly typed top-level avatar-tags field untouched.
- **Optional moderation-tag browser.** **Edit tags** remains the existing search-first flow. A new **Browse tags** button is available alongside it for moderators who prefer to scan all configured tags; it splits up to 125 tags into Discord's supported five 25-option menus and preserves selections already on the audit entry.
- **Desktop build workflow.** Pushes to `main` that affect source, the Maven project, vendored libdave, or the workflow now build Scarlet with Java 21 and attach the desktop JAR as a 30-day GitHub Actions artifact.

These contributions are adapted from [Chloethecat's Scarlet fork](https://github.com/Chloethecat/Scarlet), with the search-first tag editor intentionally retained as the default experience.

## Trust ranks

The player list now has a **Rank** column showing each player's VRChat trust rank — Visitor, New User, User, Known, Trusted — plus **Nuisance** for troll-flagged accounts. It's derived from the account's own tags, so it costs no extra API calls. VRChat only returns a complete tag set for some users, so treat it as a strong hint rather than a guarantee; in practice it's right for the large majority of joins.

Two rank-based alerts come with it:

- **Nuisance (troll-flagged):** on by default. It's flagged in the advisory column and sorted to the top of the list, the bundled **siren-chirp** alert plays, and the join callout speaks "Nuisance rank." The sound is one of your `BL_SFX_*` effects, rendered to WAV and shipped inside the build (extracted to a scratch temp file for playback, never the data folder) so it plays on every route including Discord voice. Turn the pieces off with `advisory_flag_nuisance_rank` (row/advisory) and `tts_announce_nuisance_rank` (alarm + callout).
- **Visitor:** opt-in, off by default. An optional row advisory (`advisory_flag_visitor_rank`) and an optional spoken callout (`tts_announce_visitor_rank`) for owners who want to watch for brand-new accounts. Visitors are usually fine, so this is quiet unless you ask for it.

## Faster follow-into-new-instance (~90s → ~30s)

When *Launch on Instance Create* is enabled, the bot used to take roughly a minute and a half to notice a freshly created instance, because it waited for the group **audit log** — which is limited both by the poll interval and by VRChat's own ingest lag before an entry even appears.

Scarlet now also watches the group's **live open-instance list** on a short cadence and cold-boots the client the moment a new instance shows up. It's built to stay well clear of anything VRChat would flag:

- one lightweight call per cycle, at a **tunable interval** (`instance_follow_fast_poll_seconds`, default **30s**, hard **15s** floor),
- only while *Launch on Instance Create* is on,
- backs off a full minute on any error or rate-limit response,
- deduplicated with the existing audit path, so an instance is never launched into twice.

Net effect: detection drops from ~90s to ~30s (tunable) without hammering the API.

## Readability: advisory colours

The player-list advisory colours were raw, over-saturated primaries that were harsh on a dark theme and rough to read over a Discord screenshare. They've been retuned to a calmer, higher-contrast set that reads the same at a glance but is much easier on the eyes — and **Nuisance** and **Visitor** ranks now each have their own distinct colour. This is UI-only; Discord embed colours are unchanged.

## Headless fix

When Scarlet finds more than one data folder and there's no interactive console (a headless service), the "which folder?" prompt no longer opens a blocking read on stdin — it logs the candidates and deterministically loads the first, so a headless bot can't stall at startup.

## Bans no longer logged as standalone kicks

Old bug, going back to the legacy `0.4.12`-era builds. When you ban someone who's currently in an instance, VRChat auto-issues an instance-kick alongside the ban. Scarlet is meant to recognize that kick as part of the ban and bundle it under the ban's thread rather than posting it as a separate kick.

The detection compared the new kick against the target's "most recent prior action" — but an inverted date comparison made that resolve to the target's *oldest* action instead of the newest. So for any user with prior moderation history, the co-occurring ban was missed and its auto-kick got logged as a normal kick. Fixed the comparison (it now correctly picks the newest prior action, which also corrects the "Most recent" line in the moderation embed).

Note for testing: there's a separate, intermittent timing factor — the ban's Discord thread is created a beat after the ban is posted, so if the auto-kick is processed in the very same audit poll it can occasionally still slip through before the thread exists. If you still see the odd ban-as-kick after this fix, that's the cause and I'll harden the timing.


## Outstanding-moderation re-ping fixed

The reminder that re-pings a moderator when they log an action without a reason was completely non-functional: it was never called from the run loop, and its "is this outstanding?" test was inverted. Both are fixed — it now runs, and correctly flags only actions with **no tags and no description** (skipping redacted entries and the auto-kick that gets bundled under a ban).

Per community request, **a message posted in the moderation thread now counts as a valid log** — the first message a moderator sends in the thread is captured as that action's reason, so the re-ping clears without needing tags. (Heads-up: this uses an in-memory thread→entry map, so it captures messages in threads created since the bot last started; a message in a pre-restart thread won't be captured. And the whole feature only posts if you've configured an "Outstanding Moderation" channel and enabled the relevant *Ping on outstanding…* toggles.)


## Report profile pictures / user icons to VRChat

Community idea (KyootFox): reporting in-game profile / sticker / emoji content. Sticker, emoji and print reporting already existed — Scarlet surfaces a pre-filled VRChat T&S report link on each one's in-instance spawn embed. The gap was **profile pictures and user icons**, which had a report-URL builder in the code but were never shown anywhere.

Moderation embeds now include **Report profile picture** and **Report user icon** links, pre-filled with the target user and your configured report email (Settings → "VRChat Help Desk report email"). Scarlet only assembles the report; a human clicks through and files it with VRChat T&S — no automated reporting under the bot account.


## Moderation/report data no longer drops out when the API is flaky

The bug behind "sometimes the report data doesn't appear, and it's inconsistent." The moderation embed assumed VRChat always returns the full target user, and dereferenced it in several places (name, image, join date, pronouns, status). When the API didn't — rate limits, a private profile, or a transient failure, the same flakiness behind the avatar/trust-rank gaps — the embed threw and the entire moderation log (with its report links) silently failed to post. Which user it hit was luck of the draw, so it looked random across people.

Now every user field is null-guarded and falls back to the audit entry's own IDs. The log and the pre-filled report links always post; any field VRChat didn't return is just omitted (with a short note), instead of taking the whole message down with it.

## "Edit tags" and "Manager notes" buttons work again

Both button handlers built their modal but never `.queue()`d it, so the modal was never sent and Discord showed "interaction failed" (the repeated "did not acknowledge" warnings in the logs). This was universal — not ARM-specific — and both now queue the reply.

## Report flow updated for VRChat's reporting changes

VRChat moved general reporting **in-app** and restricted the Help Desk form to **appeals and evidence-backed reports** (and it now requires signing in), so the old pre-filled Help Desk links mostly hit a login wall or get auto-closed. The `vrchat-report` button now outputs a **plain-text, copy-paste report block** — target, actor, reason, internal tags, group/audit IDs — for a signed-in moderator to paste straight into VRChat's in-app report. The Help Desk link is kept but labelled for its now-narrow use (appeals / evidence). Still no automated reporting under the bot account — a human files it.

## ARM/glibc Discord voice (DAVE) groundwork

On a glibc arm64 box (e.g. a Raspberry Pi running the desktop jar) there's no native `libdave-jvm`, so Discord voice falls back to the JNA `DAudioDaveSession` path — which mis-handshakes and makes the bot thrash (join/leave) in voice. That fallback (only ever active on such platforms) is now heavily logged at each DAVE handshake step, with the MLS-init failure surfaced at error level, so the next ARM voice attempt pinpoints exactly where E2EE dies. Also fixed two casts the original author had flagged `// smells sus`. Termux/Android arm64 users should keep using **`-android.jar`**, which bundles the proper arm64 `libdave-jvm`.


## Quieter logs: down or dead avatar-search providers

If an avatar-search provider goes down — its domain stops resolving, it starts returning HTTP 403/404, it times out, or it refuses the connection — Scarlet used to retry it on **every single search** and dump a full stack trace each time. One permanently-dead host could bury the console on its own.

Now a failing provider logs **one** warning when it first goes down and **one** when Scarlet gives up on it, then goes quiet. The stack traces for these expected network failures are gone from the normal logs (TRACE only). Every failure now backs off, and the backoff **doubles** with each repeat up to a ~2-hour cap, so a dead provider is retried a couple of times an hour at most instead of constantly. When a provider starts responding again it's picked back up automatically, with a one-line "recovered" note.

Two concrete results from the reported logs:

- **`vrcx.avtr.zip` is removed.** Its hostname no longer resolves, so it could only ever fail — it was the biggest single source of the spam.
- **avtrDB is removed entirely** — text search *and* reverse-image — because its search was rejecting requests from datacenter IPs and likely VPNs. Text search continues on the five remaining providers, and **"search by picture" was rebuilt without avtrDB** (next section) rather than left broken.

## Reverse-image search, rebuilt dependency-free

avtrDB was the only provider that answered "here's an avatar image, which avatar is it?" directly. At the time, its endpoint was unavailable to Scarlet because of datacenter/VPN filtering, so rather than leave the feature broken, "search by picture" is rebuilt from pieces Scarlet already trusts:

1. The avatar image's file ID is resolved to its **owner** via VRChat's own (authenticated) file API.
2. Each remaining provider is queried **by author** — the standard VRCX `authorId` lookup.
3. Only the avatar whose image references that same file ID is kept.

No new API key, no new dependency, and nothing that can quietly die on you the way `avtr.zip` did. Author-lookup state is tracked **separately** from text search, so if a provider doesn't support author lookup it backs off quietly for that mode without touching its text search. If the owner can't be resolved or no provider indexes them, it returns no match and falls back to name search — exactly as before.

> **Correction:** An earlier version of these notes said avtrDB required an API key. That was a misunderstanding by KozyBlake and Claude. The avtrDB maintainer confirmed that datacenter IPs (and likely VPNs) were being blocked and has since provided a bypass for Scarlet users. avtrDB can be brought back if the Scarlet community asks for it.

## Built on the VRCX avatar-search ecosystem

Worth stating plainly: Scarlet's whole avatar-search feature — text search, author lookup, and the rebuilt reverse-image — runs on the **VRCX avatar-search provider format**. That's the query convention ([VRCX](https://github.com/vrcx-team/VRCX)'s `?search=` / `?authorId=` / `?fileId=` shape, `n=5000`, and the tolerant JSON response) and the same community provider ecosystem VRCX popularised (nekosunevr, VRCDB, WorldBalancer, paw, KitsuneDB). The reverse-image rebuild also follows VRCX's own documented behaviour: try a direct file-ID lookup, otherwise resolve the owner and match by author.

To be precise about it: Scarlet **implements** that format — it doesn't bundle, fork, or import VRCX's code. Building to the shared convention is what keeps Scarlet interoperable with the same providers VRCX uses, and lets it benefit from (and contribute back to) that ecosystem.

## First-launch notice (a "vibe-coded" disclosure)

Scarlet now greets first-time users with an honest heads-up: this fork is **maintained by KozyBlake and developed largely with AI assistance** — a "vibe-coded" project — with a **Continue** or **Close Scarlet** choice. If running AI-assisted software isn't for you, you can bow out right there, no questions asked.

- Shown **once**, then remembered (`vibecoded_notice_acknowledged` in `settings.json`).
- **Headless / scheduled** bots don't get a dialog they can't answer — they log the notice once and keep running.
- The same note now sits at the top of the README.

To be clear about accountability: KozyBlake maintains and stands behind the project; the AI is a tool in that process, not the maintainer.
