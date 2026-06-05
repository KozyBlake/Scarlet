# Scarlet 0.4.17-b2

_KozyBlake fork - Windows, Linux, and Android-support build._

## Highlights

This release focuses on making TTS moderation callouts easier to understand in VR, while also cleaning up the desktop JAR after RVC was removed.

- **Mixed-character username alerts are shorter and clearer.** When a joined user's display name mixes scripts or lookalike letters, Scarlet now plays a bundled alert sound and then says: `Mixed-letter name joined. Check nameplates.`
- **TTS avoids noisy script-by-script spelling for mixed names.** Mixed-script names now use Scarlet's best readable/transliterated form instead of announcing language labels for individual characters.
- **Readable lookalikes are treated as readable names.** Latin-looking confusables are mapped toward their visual English form for TTS callouts, while full non-Latin names are still handled as non-Latin names instead of being forced through the lookalike path.
- **The main desktop JAR is smaller.** RVC, the old lite/minimal shaded editions, and dormant staged browser automation code have been removed.
- **Android DAVE natives stay with the Android artifact.** The desktop JAR no longer carries Android/Termux DAVE native payloads, while the Android classifier build still includes them automatically.

## Downloads

| Artifact | Size | Contains |
| --- | ---: | --- |
| `scarlet-0.4.17-b2.jar` | 42.4 MiB | Main desktop Scarlet build for Windows/Linux |
| `scarlet-0.4.17-b2-android.jar` | 57.0 MiB | Android-support classifier with Android/Termux DAVE native payloads |

The old lite and minimal JAR variants are no longer produced. With RVC removed, the main desktop JAR is the normal Scarlet download.

## What's new

### Mixed-character TTS alert

Scarlet now bundles `tts/mixed-character-alert.wav` inside the application JAR. On startup, the TTS service extracts that sound into Scarlet's TTS data folder so it can be played before the spoken alert.

For mixed-character joins, the audio flow is now:

1. Play the bundled alert sound.
2. Speak `Mixed-letter name joined. Check nameplates.`
3. Keep normal watched-user, watched-group, and new-player TTS callouts readable using the sanitized display name.

This is meant for moderators in VR who cannot quickly switch to Discord or another desktop UI while the announcement is playing.

### Better readable-name handling

The username sanitizer now has two different paths:

- Latin-looking confusables are converted toward what they visually look like so TTS can say the readable name.
- Mixed-script names skip the old "Cyrillic / Greek / ..." script-label callout and use best-effort transliteration instead.
- Single-script non-Latin names still keep a short script prefix when that is clearer than pretending the name is English.

The goal is to make TTS useful without turning every suspicious display name into a long, distracting spelling lesson.

### Startup security checks

The security regression checks were moved out of the test tree and into Scarlet's main source tree. They are now bundled into the main JAR and run during normal Scarlet startup.

These checks cover the security-sensitive helpers that were previously easy to leave behind during packaging or release work, including encrypted preference handling and public URL validation behavior.

## Packaging changes

### RVC removed

RVC voice conversion was removed from Scarlet. This release no longer ships:

- RVC Java service/config/UI code.
- The bundled RVC Python bridge.
- RVC model-manager and install dialogs.
- RVC-related settings rows.

This removes a personal-experiment feature that was not useful enough for normal Scarlet releases.

### Lite and minimal editions removed

The lite edition existed mainly to avoid shipping RVC. Since RVC is gone, the extra edition is no longer needed.

The minimal edition was also removed. Scarlet now builds the main desktop JAR and the Android-support classifier instead of producing full, lite, and minimal variants.

### Desktop JAR slimmed down

The main desktop shade now excludes Android/Termux DAVE native payloads. E2EE/DAVE support remains available where it is needed:

- Desktop DAVE/native support remains in the main Scarlet JAR.
- Android/Termux DAVE native payloads remain in `scarlet-0.4.17-b2-android.jar`.

This keeps the normal desktop download smaller without dropping DAVE support from the Android artifact.

### Dormant staged browser code removed

The old staged browser/web automation tree was removed. This included dormant NanoHTTPD/JCEF/Selenium experiment code that was not used by Scarlet's active report flow.

The current help-desk/report-link behavior remains intact.

## Update metadata

`meta.json` now advertises `0.4.17-b2` for both `latest_release` and `latest_build`, so users running older Scarlet builds will receive the update notification through Scarlet's normal update check.

## Compatibility notes

- No config migration is required.
- Existing TTS settings remain compatible.
- The mixed-character alert can be toggled with the existing mixed-character TTS setting.
- RVC settings from older configs are no longer used.
