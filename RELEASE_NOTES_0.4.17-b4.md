# Scarlet 0.4.17-b4 Release Notes

A moderation-callout and settings-reliability release. The headline changes are shorter, less noisy TTS callouts and a much smarter suspicious-pronoun check, plus two settings fixes that make configuration behave the way you'd expect.

## What's New

### Condensed TTS join callouts

When a flagged user joins, Scarlet used to play several separate spoken clips back-to-back (watched user, then watched group, then new account, then pronouns). Those are now combined into a **single spoken line per user**:

> "Vex joined the lobby. Watched groups: Raiders, Trolls. New, 5 days. Suspicious pronouns: ur/mom."

- Multiple watched groups are now read **together** in one callout instead of just the first.
- Filler wording was trimmed across the board (vote-to-kick and watched-avatar lines are shorter too).
- Raw pronoun audio and the script-name prefixes for non-Latin names are unchanged.
- New setting: **TTS: Announce suspicious pronouns** — toggle the spoken pronoun warning independently, matching the other announce switches.

### Reworked suspicious-pronoun detection

The old check flagged almost anything that wasn't a bare, perfectly-formatted pronoun set — so harmless fields tripped it constantly while short abusive ones slipped through. The detection has been rebuilt around two ideas:

- **Recognize real pronouns.** Scarlet now knows the common pronoun sets, including neopronouns — `xe/xem`, `ze/hir`, `ze/zir`, `fae/faer`, `ey/em`, `e/em/eir` (Spivak), `ae/aer`, `co/cos`, `hu/hum`, `ne/nem`, `per/per`, `thon/thons`, `ve/ver`, `vi/vir`, `zhe/zher`, `ki/kin`, and more. If a field **contains** a legitimate pronoun, it passes even with extra words next to it — so `she/her puppy` no longer false-flags.
- **Always catch genuine abuse.** Self-harm and violence directives (`kill/yourself`, `kys`, including basic leetspeak like `k1ll y0urself`), deny-listed terms (even when embedded in a longer string), and links / Discord invites are flagged first — even if a real pronoun is also present.

The default allow- and deny-lists are seeded much more thoroughly and are still fully editable in `good_pronoun.json` / `bad_pronoun.json`. Edits take effect on the next player join — no restart needed.

### Settings fixes

- **Dropdowns keep their saved value.** Dropdown settings (e.g. the mobile companion's *minimum severity*) reset to their default every time the Settings tab was rebuilt, making a saved value look like it hadn't saved — and re-selecting the shown default could silently overwrite it. They now display the value that's actually saved.
- **Discord commands update the desktop UI live.** Changing a setting via a Discord command (for example `/set-verification-auto-invite`) now updates the matching fields in the desktop Settings tab immediately. This fixes a listener-registry bug where the UI updater could be silently dropped, leaving the window showing stale values.

### Safer verification auto-invite default

The default **VRChat group to auto-invite verified members to** was previously a real group. It's now an obvious placeholder, so enabling auto-invite before configuring a group can no longer invite members into the wrong group — the invite simply reports "no group configured" until you set a real one.

---

## Files in this release

| File | Description |
|------|-------------|
| `scarlet-0.4.17-b4.jar` | Scarlet desktop application |
| `scarlet-0.4.17-b4-android.jar` | Scarlet Android application |

The **Scarlet Companion** Android app is unchanged since `0.4.17-b3`; keep using the APK from that release.

## Upgrading

Drop in the new desktop JAR as usual. No settings migration is required. If you previously set a dropdown setting and it appeared to "reset," re-check it once after updating so it reflects what you actually want.
