# Security & Data Transparency

Scarlet is a self-hostable VRChat group-moderation tool. It runs on your own
machine, signs in to *your* VRChat account and *your* Discord bot, and stores its
data locally. This document is a plain-language inventory of everything Scarlet
does that touches the network, your credentials, or your operating system — so you
can verify what it does instead of taking anyone's word for it (including ours).

Nothing here is hidden in the code; this document just collects it in one place.
If you find something that contradicts this document, that's a bug — please report
it (see the bottom).

---

## 1. Network connections

Scarlet only contacts a network destination for a specific, visible feature. The
table below lists every category of outbound connection, what it's for, when it
happens, and how to turn it off.

| Destination | Purpose | When | How to disable |
|---|---|---|---|
| `api.vrchat.cloud`, `vrchat.com` | The VRChat API — the entire point of the tool (audit log polling, bans, group management, user lookups). | Continuously, while signed in. | Don't sign in / don't run Scarlet. |
| `discord.com`, `gateway.discord.gg` | Your Discord bot (JDA) — command handling and log posting. | While the bot is connected. | Don't configure a bot token. |
| `raw.githubusercontent.com/<fork>/main/meta.json` | Update & announcement check (reads a small JSON file for the latest build number and any maintainer notice). Read-only; see §4. | Periodically and on manual check. | It only reads a public text file; ignore the notices. |
| `api.github.com`, `jitpack.io`, `repo1.maven.org` | Version discovery for Scarlet and the bundled VRChat API library (which build numbers exist). | On update/version checks. | Cosmetic; failure just means "update check unavailable." |
| `fcm.googleapis.com`, `oauth2.googleapis.com` | Firebase Cloud Messaging push, **only** if you enable the mobile companion and configure a Firebase service account. | Only when mobile push is configured. | Leave the mobile companion off (default). |
| `peachpuff-swan-183728.hostingersite.com` | Default **relay** for mobile push notifications to a paired phone. | **Only** if the mobile companion is enabled *and* using the relay transport. | Mobile is **off by default** (`mobile_enabled = false`). You can also point the relay at your own server. |
| Avatar-search providers (`avatarsearch.cc`, `avtrdb.com`, `vrcdb.*`, `worldbalancer.com`, `nekosunevr.co.uk`, and similar) | The optional avatar-search feature, which queries community VRChat avatar databases (VRCX-compatible). | Only when you run an avatar search, if the feature is enabled. | Disable avatar search, or edit/reset the provider list in Settings → Avatar Search. |
| `tenor.com` | Fetches a GIF for the `/vrchat-animated-emoji` command. | Only when you run that command with a Tenor URL. | Don't use the command. |
| `scarlet.sybyline.net` | Builds a shareable calendar-event **link** (encodes the already-public group ID + event ID). This constructs a URL string; it is **not** a data upload. | When posting a scheduled event link. | Don't use event scheduling. |

Everything Scarlet fetches from a URL you didn't hard-code (avatar providers, image
links, webhooks) is run through a **public-address filter** first — see §6.

## 2. What can control Scarlet remotely

There are exactly three ways to send Scarlet a command, and none of them is a
hidden channel:

- **Your Discord bot.** Slash commands are the main remote surface. They come from
  *your* bot in *your* server, and each command is gated by Discord permissions and
  by Scarlet's own permission→role mapping (viewable with `/config-info`). Whoever
  controls the server controls this — no one else.
- **Local IPC pipe.** A named pipe (`\\.\pipe\ScarletIPC-<groupid>` on Windows,
  `/tmp/ScarletIPC-<groupid>.sock` elsewhere) accepts CLI commands **from the same
  machine only**. It is not a network socket and is not reachable remotely.
- **Mobile companion LAN listener.** A local-network listener, active **only** if
  you enable the mobile companion with the direct transport (off by default).

## 3. Credential storage

Scarlet stores your VRChat login and 2FA secret so it can re-authenticate
unattended. They are encrypted at rest:

- Cipher: **AES/GCM/NoPadding** (authenticated encryption, 128-bit tag).
- Key derivation: **PBKDF2WithHmacSHA256**.
- Implemented in `util/EncryptedPrefs.java`; correctness (encrypt/decrypt round-trip
  and migration from the legacy key derivation) is covered by
  `util/SecurityRegressionChecks.java`.

Credentials stay on your machine. They are sent only to VRChat's own login
endpoint, over HTTPS, exactly as a normal VRChat login would.

## 4. Update & announcement mechanism

This is the feature most often mistaken for a "backdoor," so here is exactly how it
works.

**Update check.** Scarlet reads a small public file, `meta.json`, on the fork's
`main` branch. It contains the latest build number and an optional `announcement`
text field. The check is **read-only** — it fetches text and, at most, shows you a
notice. It downloads and runs nothing.

**Announcements.** If `meta.json` contains an `announcement` object, Scarlet
displays it as a notice (for example, "VRChat API change expected tomorrow"). It is
a one-way public message board: it is de-duplicated by `id`, can carry an `expires`
timestamp, and **executes no code**. It exists so maintainers can warn running
instances about upstream breakage.

**Applying an update.** The `update-now` command (or the desktop equivalent) does
**not** download or execute a JAR from inside the running process. `Scarlet.update()`:

1. checks that the requested version is a real, known public build;
2. writes that version string to a local file, `scarlet.version.target`;
3. exits with status code `70`.

An **external launcher** (the script/bootstrap you use to start Scarlet) is what
reads `scarlet.version.target`, fetches that published build from the public source,
and relaunches. A plain `restart` (exit code `69`) simply relaunches the **same**
build. In other words, the Scarlet process never pulls code into itself — it records
a desired version and exits. The trust boundary is the public build source and your
own launcher, both of which you can inspect.

## 5. Process execution

Scarlet launches OS processes only for ordinary desktop tasks:

- Launch VRChat (via Steam or the client executable) — the "open instance" feature.
- Detect whether VRChat is running (`tasklist` on Windows, `pgrep` elsewhere).
- Open a URL or a folder (`xdg-open` / the desktop browse API).
- Relaunch/restart itself (see §4).
- Load Discord's DAVE native library (end-to-end encryption for Discord voice).

No process is a shell interpreting remote input.

## 6. Outbound request safety (SSRF protection)

Any URL Scarlet fetches that originates from data rather than a hard-coded constant
is validated first, and the validation is covered by a regression test suite
(`util/SecurityRegressionChecks.java`). Requests are **rejected** when the target
resolves to:

- loopback (`127.0.0.0/8`, `::1`), private ranges (`10/8`, `172.16/12`,
  `192.168/16`), CGNAT (`100.64/10`), link-local **including the cloud-metadata IP
  `169.254.169.254`**, TEST-NETs, multicast, reserved space, and IPv6 ULA/link-local;
- non-HTTP schemes (`file://`, `javascript:`), opaque URIs, and URLs carrying
  embedded credentials (`user:pass@host`).

Redirects are re-validated and the final target is pinned, which blocks
DNS-rebinding-style tricks. This is the opposite of what malicious software does —
it is there specifically to stop Scarlet from being pointed at your internal network
or a cloud metadata endpoint.

## 7. Native code & dependencies

The only native library Scarlet loads is `dave-jvm` (Discord's DAVE protocol for
end-to-end-encrypted voice), bundled and loaded via `System.load`. There is **no**
loading of code over the network, no `URLClassLoader` pointed at a remote source,
and no scripting-engine/`eval` path. Reflection is used only to select the correct
IPC-pipe constructor and to detect the Android runtime.

Dependencies (JDA, the VRChat API library, OkHttp, etc.) are standard, and their
versions are pinned in the build. A supply-chain issue would live in a dependency,
not in Scarlet's own code — auditing them is part of a full review.

## 8. Why antivirus sometimes flags Scarlet

A Java application that self-relaunches, loads a native library, spawns OS processes,
and reads stored credentials matches several antivirus heuristics for "trojan/
backdoor," even though every one of those behaviors is a documented feature above.
An AV flag on the shipped JAR is almost always a **heuristic false positive**, not
evidence of malicious code. Building from source (below) and submitting the JAR to
the AV vendor as a false positive are the right responses.

## 9. Verifying this yourself

You do not have to trust this document. You can confirm all of it:

1. **Build from source.** Build the JAR from this repository and run that, rather
   than a prebuilt binary. Then the code you audited is the code you run.
2. **Compare against a checksum.** Each release should publish a checksum; verify the
   binary you downloaded matches.
3. **Diff the fork against upstream.** This is a fork of
   `SybylineNetwork/Scarlet`. Diffing against upstream shows exactly what this fork
   changed — the honest answer to "did the fork add something."
4. **Watch the traffic.** Run Scarlet behind a proxy or packet capture and confirm
   the destinations match §1.
5. **Read the sensitive files directly:** `util/EncryptedPrefs.java`,
   `util/HttpURLInputStream.java`, `util/SecurityRegressionChecks.java`,
   `ScarletMobile.java`, and the `update()`/`meta.json` code in `Scarlet.java`.

## 10. Reporting a real vulnerability

If you find a genuine security issue — something this document is wrong about, or a
real hole — please open an issue or contact the maintainer privately rather than
posting an exploit publicly. Concrete reports (a file, a line, a captured request)
can be fixed; rumors cannot.
