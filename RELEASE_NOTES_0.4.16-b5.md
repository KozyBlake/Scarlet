# Scarlet 0.4.16-b5

_KozyBlake fork — Windows & Linux build._

## Highlights

This release splits Scarlet into two editions and lands a batch of fixes for the RVC voice-conversion pipeline.

- **New `lite` edition.** Every `mvn package` now produces two JARs side-by-side. Users who don't want RVC can grab the lite build and never see the voice-conversion UI, the Python dependency installer, or the torch/rvc-python footprint.
- **New RVC pitch setting.** A `tts_rvc_pitch` slider (±24 semitones) in Settings → Text-to-Speech so models trained on a different fundamental than your TTS voice can be pulled back into the right range.
- **RVC actually works on PyTorch 2.6+.** The fairseq hubert checkpoint load that started failing with `_pickle.UnpicklingError: Weights only load failed` is fixed, along with the downstream "Expected a JsonObject" parse error and a handful of install-flow papercuts.

## Downloads

| JAR | Size | Contains |
| --- | ---- | -------- |
| `scarlet-0.4.16-b5.jar` | full edition | Everything — TTS, RVC voice conversion, Python-bridge install flow |
| `scarlet-0.4.16-b5-lite.jar` | lite edition | Everything except RVC. No pitch setting, no model manager, no Python dependency install prompts |

Pick one. If you don't know what RVC is, you probably want the lite JAR.

Both editions are runnable with `java -jar scarlet-0.4.16-b5[-lite].jar` and have the same classpath/manifest.

## What's new

### Lite edition (`scarlet-0.4.16-b5-lite.jar`)

The full and lite JARs are built from the same source tree by the same `mvn package` invocation. The lite build is produced by a second `maven-shade-plugin` execution that strips:

- `scarlet-features.properties` — so the runtime `Features.RVC_ENABLED` flag falls back to its compile-time default (`false`).
- `rvc/rvc_bridge.py` — the ~100 KB Python bridge script that wraps rvc-python.

At startup, the lite edition:

- Skips RVC dependency checks and install dialogs entirely.
- Doesn't instantiate `RvcService` (the singleton stays null).
- Hides every `tts_rvc_*` / `rvc_*` setting from the UI, so the Text-to-Speech section renders without any RVC rows.

The RVC Java classes are still compiled in — they just never execute — so there's no code-path divergence between editions beyond `if (Features.RVC_ENABLED)` gates. The flag is `public static final boolean`, so the JIT dead-code-eliminates disabled branches and there's no runtime cost.

### RVC pitch setting

New slider under Settings → Text-to-Speech labelled **TTS: RVC pitch (semitones)**, range `-24` to `+24`, default `0`. Positive values raise the pitch before the RVC model does its conversion. Common starting points:

- `+12` — feeding a male TTS voice into a female-trained model.
- `-12` — feeding a female TTS voice into a male-trained model.
- `0` — leave alone if your TTS voice and model already agree on fundamental.

Tuning this is the easiest way to fix a model that sounds "wrong but recognisable" — it usually means the training-voice fundamental was off from what you're feeding in.

### RVC bridge: `--models-dir` flag

The bridge now accepts an explicit `--models-dir` argument passed by the Java side, so it finds `.pth` models and `.index` files even when the bridge script is extracted to a different location than the user's models directory.

### RVC bridge: index matching by stem-contains

A model like `BlakeBelladonnav2.pth` is now matched against any `*BlakeBelladonna*.index` file, instead of requiring an exact filename prefix. This matches how RVC-WebUI users typically name their companion retrieval files.

## Bug fixes

### PyTorch 2.6+ compatibility

rvc-python 0.1.5 calls `torch.load()` without specifying `weights_only`. In PyTorch 2.6 the default flipped from `False` to `True`, which makes fairseq's hubert checkpoint fail to deserialise because it pickles `fairseq.data.dictionary.Dictionary`:

```
_pickle.UnpicklingError: Weights only load failed. This file can still be loaded,
to do so you have two options... Please file an issue with the following so that we
can make `weights_only=True` compatible with your use case...
```

The bridge now:

- Allowlists `fairseq.data.dictionary.Dictionary` (+ a few other classes rvc-python commonly pickles) via `torch.serialization.add_safe_globals`.
- Monkey-patches `torch.load` to restore `weights_only=False` as the default — only when the caller didn't supply one, and only once (guarded by a `_scarlet_patched` sentinel).

### JSON parse error from stdout leakage

Conversions were failing with:

```
com.google.gson.JsonSyntaxException: Expected a JsonObject but was JsonPrimitive
  at net.sybyline.scarlet.util.rvc.RvcService.doConversion(...)
```

…because rvc-python prints progress to stdout (`overwrite preprocess and configs.json`, `Model X.pth loaded.`, `gin_channels: 256 self.spk_embed_dim: 109`), which clobbers the JSON result Scarlet expects. Fixed two ways:

- The bridge now wraps `RVCInference` creation and `infer_file` inside `contextlib.redirect_stdout(sys.stderr)`, so library prints go to stderr (where the Java stderr drainer routes them to DEBUG) and stdout stays clean.
- `RvcService.doConversion` now extracts the last balanced `{...}` JSON object from stdout and parses only that slice, as defence in depth against any future library also leaking prints.

### Dependency install flow

- **CUDA wheel picker.** The version comparison `major >= 12 and minor >= 4` was false for CUDA 13.0, pushing users with newer GPUs down to the CPU wheel. Switched to tuple comparison; CUDA ≥ 12.4 now correctly selects the `cu124` wheel index.
- **`WinError 5 — Access is denied`.** The install flow was calling `pip install --upgrade torch torchaudio` unconditionally, failing on Windows because Scarlet already had the DLLs memory-mapped from `--status`. The reinstall step is now skipped entirely when both packages are already present.
- **Stale import caches.** After a successful pip install, `_try_import("torchcodec")` would still return None because Python's `FileFinder` and `importlib.metadata` caches held pre-install state. The bridge now calls `importlib.invalidate_caches()` and re-runs `importlib.metadata.distributions()` after every pip step.
- **`torchcodec` incorrectly gating compatibility.** `rvc_python` 0.1.5 doesn't actually import torchcodec, but it was included in the required-packages set, so `rvc_compatible` would return false when torchcodec was missing. Split into `REQUIRED_PACKAGES` (torch, torchaudio, rvc_python) and `OPTIONAL_PACKAGES` (torchcodec).

### Bridge-extraction cache

Stale bridge scripts in AppData were being preferred over a fresh classpath copy, so bridge fixes shipped in a new JAR wouldn't take effect until users manually deleted their AppData copy. `RvcService.getResourcePath()` now extracts from the classpath first and only falls through to the AppData fallback when no classpath resource exists — so upgrades work automatically.

### `java.io.IOException: The handle is invalid` on Windows launches without a console

A long-standing bug that only reproduced on some users' machines:

```
[ERROR] [Scarlet] Exception in spin
java.io.IOException: The handle is invalid
    at java.base/java.io.FileInputStream.available0(Native Method)
    at java.base/java.io.FileInputStream.available(FileInputStream.java:415)
    at java.base/java.io.BufferedInputStream.available(BufferedInputStream.java:408)
    at net.sybyline.scarlet.Scarlet.spin(Scarlet.java:...)
```

`Scarlet.spin()` polled `System.in.available()` every 100 ms to pick up console commands. On Windows this call is backed by the Win32 API, and if the process has no attached console the underlying handle is invalid and `available0()` throws `ERROR_INVALID_HANDLE` (Win32 error 6). The polling loop then re-threw and logged the same stack trace ten times a second, flooding `scarlet.log`.

Conditions that trigger this:

- **Double-clicking the JAR.** Windows' default `.jar` association is `javaw.exe`, which allocates no console.
- **Windows shortcuts** pointing at `javaw -jar scarlet.jar`.
- **Task Scheduler** entries running Scarlet without an interactive session.
- **Detached launches** — `start /b java -jar`, `cmd /c start javaw`, service wrappers, etc.

The reason this only affected "some people" is that anyone who launched Scarlet from `cmd`, PowerShell, Git Bash, or Windows Terminal got a real `java.exe` console and never saw it.

Fixed by detecting the condition on the first failure: `spin()` now catches `IOException` from `available()`, flips a `stdinUsable` flag to `false`, and logs a single INFO-level line ("Console stdin unavailable (...); CLI commands disabled for this session"). Subsequent ticks short-circuit on the flag, so there's no more per-tick native call and no more ERROR spam.

The in-app CLI panel (added in 0.4.16-b4) continues to work on all launch styles — only the `System.in` reader is disabled, and only when there's genuinely no console to read from.

### `ErrorResponseException: -1: java.io.InterruptedIOException` on shutdown

Another bug that reproduced only for some users:

```
[ERROR] [JDA-I/requests/Requester] There was an I/O error while executing a REST request: null
[ERROR] [JDA/requests/RestAction] RestAction queue returned failure
net.dv8tion.jda.api.exceptions.ErrorResponseException: -1: java.io.InterruptedIOException
    ...
    at net.sybyline.scarlet.ScarletDiscordJDA.lambda$updateCommandList$0(ScarletDiscordJDA.java:352)
    ...
Caused by: java.io.InterruptedIOException
    at okhttp3.internal.http2.Http2Stream.waitForIo$okhttp(Http2Stream.kt:716)
    ...
[INFO] [Scarlet] Finished shutdown flow
```

On startup, when Scarlet detects that the version has changed since the last run, it calls `updateCommandList()` to push every slash command to Discord (one REST call per upsert/delete/edit). If the user closes Scarlet **before that REST queue has drained**, JDA's shutdown interrupts the in-flight OkHttp HTTP/2 streams, `Http2Stream.waitForIo` throws `InterruptedIOException`, and JDA wraps it as `ErrorResponseException(-1, …)` — the `-1` response code means "IO-layer error, no HTTP response". JDA's shipped default failure handler logs everything at ERROR with a stack trace, which users understandably read as a bug.

Why "some users" saw it:

- **Only after an update.** `updateCommandList()` is gated on `checkHasVersionChangedSinceLastRun()`; cold-starts on an unchanged version never enter this code path.
- **Only if shutdown happens mid-sync.** Fast connections drain ~30 commands in a second or two; users who left Scarlet running for a while never saw it. Users who started Scarlet right after an update and closed it quickly caught the race window.

Fixed in two places:

1. **Custom default-failure handler** (`ScarletDiscordJDA.handleRestFailure`). Installed via `RestAction.setDefaultFailure` before any REST call fires. It walks the throwable's cause chain; if it finds an `InterruptedIOException`, it logs at DEBUG and returns. Anything else is forwarded unchanged to JDA's original default handler (captured at class-load time into `DEFAULT_REST_FAILURE_DELEGATE`) so real failures are still surfaced with full context.
2. **Inverted `awaitShutdown` check in `close()`.** `awaitShutdown(timeout)` returns `true` on completion and `false` on timeout, so the previous code was force-shutting-down JDA only when graceful shutdown had already finished (no-op) and doing nothing when it actually timed out (requests in the rate-limit queue hung indefinitely). Now correctly force-shuts-down on timeout, and preserves the interrupt status via `Thread.currentThread().interrupt()` if the close thread itself is interrupted.

Net effect: a clean shutdown during command sync no longer produces any ERROR logs, and force-shutdown actually runs when graceful shutdown stalls.

## Upgrade notes

- **No config migration required.** The new `tts_rvc_pitch` setting defaults to 0 on first run, which matches the previous hardcoded behaviour.
- **Settings file remains compatible.** The lite edition reads the same `config.json` as the full edition; any `tts_rvc_*` entries it finds are simply ignored.
- **Windows users on PyTorch 2.6+** who were seeing hubert UnpicklingErrors no longer need the `--weights_only=False` workaround — the bridge handles it transparently.
- **Users who previously deleted `%APPDATA%/scarlet/tts/rvc/rvc_bridge.py`** to force a refresh can stop doing that; the bridge is now re-extracted on every startup.

## Known issues

- The `original-scarlet-0.4.16-b5.jar` that appears in `target/` during `mvn package` is Maven's pre-shade backup of the thin JAR — not a third edition. Ignore or delete it.
- The lite edition still reads/writes `tts_rvc_*` entries in the on-disk config if they were already there (they're just not shown in the UI). This is intentional so switching editions doesn't lose settings.

---

_Full diff: [CHANGELOG.md](CHANGELOG.md)_
