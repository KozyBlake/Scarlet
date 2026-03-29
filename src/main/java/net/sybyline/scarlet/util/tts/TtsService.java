package net.sybyline.scarlet.util.tts;

import java.awt.Component;
import java.io.Closeable;
import java.io.File;
import java.text.Normalizer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;

import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.ScarletDiscord;
import net.sybyline.scarlet.ScarletEventListener;

public class TtsService implements Closeable
{

    public TtsService(File dir, ScarletEventListener eventListener, ScarletDiscord discord)
    {
        this(dir, eventListener, discord, null);
    }

    public TtsService(File dir, ScarletEventListener eventListener, ScarletDiscord discord, Component parentComponent)
    {
        Scarlet.LOG.info("Initializing TTS service...");
        this.provider = TtsProvider.select(dir.toPath(), parentComponent);
        this.eventListener = eventListener;
        this.discord = discord;
        Scarlet.LOG.info("TTS service initialized with provider: {}", this.provider.getClass().getSimpleName());
    }

    final TtsProvider provider;
    final ScarletEventListener eventListener;
    final ScarletDiscord discord;

    /**
     * Single-threaded executor that serialises all audio playback.
     * Each TTS clip is played to completion before the next one starts,
     * preventing multiple clips from overlapping.
     */
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TTS-Playback");
        t.setDaemon(true);
        return t;
    });

    public List<String> getInstalledVoices()
    {
        return this.provider.voices();
    }

    // ── Name sanitisation ──────────────────────────────────────────────────────

    /**
     * Prepares a VRChat display name for safe TTS output.
     *
     * <p>VRChat allows display names containing characters that English TTS
     * engines silently skip: invisible control/format codepoints, private-use
     * characters, and — most commonly — letters from non-Latin scripts such as
     * Cyrillic, Arabic, CJK, Hangul, Thai, Hebrew, Devanagari, etc. When any
     * of these are skipped the engine produces silence with no error, leaving
     * moderators confused about who was called out.
     *
     * <p>Processing pipeline:
     * <ol>
     *   <li><b>NFKC normalisation</b> — resolves compatibility forms
     *       (ﬁ→fi, fullwidth A→A, circled 1→1, etc.)</li>
     *   <li><b>Strip invisible/control codepoints</b> — FORMAT, CONTROL,
     *       PRIVATE_USE, lone SURROGATE, stacked NON_SPACING_MARK,
     *       Specials block U+FFF0–FFFF</li>
     *   <li><b>Replace non-Latin script runs</b> — consecutive codepoints
     *       from the same non-Latin Unicode script are collapsed into a single
     *       spoken token, e.g. {@code [Cyrillic]}, {@code [Arabic]},
     *       {@code [CJK]}, {@code [Thai]}, etc. Latin letters, digits, spaces,
     *       and common punctuation are preserved verbatim.</li>
     *   <li><b>Decide the output</b>:
     *     <ul>
     *       <li>Fully empty after processing → "a user with an unpronounceable name"</li>
     *       <li>Modified → append ", with non-Latin characters in their name"
     *           so moderators know the spoken form differs from the display name</li>
     *       <li>Unchanged → return as-is</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param displayName raw VRChat display name, may be null
     * @return a TTS-safe representation, never null or empty
     */
    public static String sanitizeName(String displayName)
    {
        if (displayName == null || displayName.isEmpty())
            return "an unnamed user";

        // ── Step 1: NFKC ──────────────────────────────────────────────────────
        String nfkc = Normalizer.normalize(displayName, Normalizer.Form.NFKC);

        // ── Step 2: strip invisible / control codepoints ──────────────────────
        StringBuilder stripped = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); )
        {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT
             || type == Character.CONTROL
             || type == Character.PRIVATE_USE
             || type == Character.SURROGATE
             || type == Character.NON_SPACING_MARK)
                continue;
            if (cp >= 0xFFF0 && cp <= 0xFFFF)
                continue;
            stripped.appendCodePoint(cp);
        }

        // ── Step 3: replace non-Latin script runs with spoken tokens ──────────
        String input = stripped.toString();
        StringBuilder out = new StringBuilder(input.length() + 32);
        boolean modified = false;

        // Track the script of the previous non-Latin run so we collapse
        // consecutive codepoints from the same script into one token.
        Character.UnicodeScript lastNonLatinScript = null;

        for (int i = 0; i < input.length(); )
        {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);

            // Digits, spaces, and common ASCII punctuation always pass through
            if (cp < 0x80 || Character.isDigit(cp))
            {
                lastNonLatinScript = null;
                out.appendCodePoint(cp);
                continue;
            }

            Character.UnicodeScript script;
            try
            {
                script = Character.UnicodeScript.of(cp);
            }
            catch (IllegalArgumentException ex)
            {
                // Unknown script — skip silently
                modified = true;
                lastNonLatinScript = null;
                continue;
            }

            // Scripts an English TTS voice can handle without issue
            if (script == Character.UnicodeScript.LATIN
             || script == Character.UnicodeScript.COMMON
             || script == Character.UnicodeScript.INHERITED)
            {
                lastNonLatinScript = null;
                out.appendCodePoint(cp);
                continue;
            }

            // Non-Latin script — replace with a spoken token
            modified = true;
            if (script != lastNonLatinScript)
            {
                // Start of a new non-Latin run: emit the script name token
                String scriptName = friendlyScriptName(script);
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ')
                    out.append(' ');
                out.append('[').append(scriptName).append(']');
                lastNonLatinScript = script;
            }
            // else: same script as previous codepoint — already emitted token, skip
        }

        String result = out.toString().trim();
        // Collapse multiple spaces that may have formed between tokens
        result = result.replaceAll(" {2,}", " ");

        // ── Step 4: decide the final output ──────────────────────────────────
        if (result.isEmpty())
            return "a user with an unpronounceable name";

        if (modified)
            return result + ", with non-Latin characters in their name";

        return result;
    }

    /**
     * Returns a short, human-friendly spoken label for a Unicode script,
     * suitable for use in a TTS token like "[Cyrillic]".
     */
    private static String friendlyScriptName(Character.UnicodeScript script)
    {
        switch (script)
        {
            case CYRILLIC:    return "Cyrillic";
            case ARABIC:      return "Arabic";
            case HAN:         return "CJK";
            case HIRAGANA:    return "Japanese";
            case KATAKANA:    return "Japanese";
            case HANGUL:      return "Korean";
            case THAI:        return "Thai";
            case HEBREW:      return "Hebrew";
            case DEVANAGARI:  return "Devanagari";
            case GREEK:       return "Greek";
            case GEORGIAN:    return "Georgian";
            case ARMENIAN:    return "Armenian";
            case MYANMAR:     return "Myanmar";
            case KHMER:       return "Khmer";
            case TIBETAN:     return "Tibetan";
            case ETHIOPIC:    return "Ethiopic";
            case SINHALA:     return "Sinhala";
            case TAMIL:       return "Tamil";
            case TELUGU:      return "Telugu";
            case KANNADA:     return "Kannada";
            case MALAYALAM:   return "Malayalam";
            case BENGALI:     return "Bengali";
            case GUJARATI:    return "Gujarati";
            case GURMUKHI:    return "Gurmukhi";
            case LAO:         return "Lao";
            case MONGOLIAN:   return "Mongolian";
            case CHEROKEE:    return "Cherokee";
            default:          return "non-Latin";
        }
    }

    // ── TTS submission ─────────────────────────────────────────────────────────

    /**
     * When true, all calls to {@link #submit} are silently dropped rather than
     * queued. Intentionally drop-not-queue: unpausing should not trigger a
     * flood of stale announcements from while TTS was paused.
     */
    private volatile boolean paused = false;

    public boolean isPaused()
    {
        return this.paused;
    }

    /**
     * Pauses or unpauses TTS output. While paused, submitted announcements are
     * dropped immediately. Existing audio that is already playing is not
     * interrupted.
     */
    public void setPaused(boolean paused)
    {
        this.paused = paused;
        Scarlet.LOG.info("TTS: {}", paused ? "paused" : "resumed");
    }

    public boolean togglePaused()
    {
        boolean next = !this.paused;
        this.setPaused(next);
        return next;
    }

    public CompletableFuture<Void> submit(String marker, String text)
    {
        if (this.paused)
        {
            Scarlet.LOG.debug("TTS({}): Dropped — TTS is paused", marker);
            return CompletableFuture.completedFuture(null);
        }
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        final String voiceName = this.eventListener.getTtsVoiceName();
        Scarlet.LOG.info("TTS: Submitting text '{}' with voice '{}' for marker {}", text, voiceName, marker);
        final String finalText = text;
        return this.provider
            .speak(finalText, voiceName, 1.0F, 1.0F)
            .thenCompose(path ->
            {
                // A null path means the voice failed (e.g. an Online/Natural voice that
                // cannot write to a file stream). Trigger fallback voice selection and retry
                // the same text with that voice so the user's command isn't lost.
                if (path == null)
                {
                    String fallbackVoice = this.eventListener.fallbackTtsVoice(voiceName);
                    if (fallbackVoice != null)
                    {
                        Scarlet.LOG.info("TTS({}): Retrying with fallback voice '{}'", marker, fallbackVoice);
                        return this.provider.speak(finalText, fallbackVoice, 1.0F, 1.0F);
                    }
                    // No fallback available — complete with null, exceptionally handler logs it
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(path);
            })
            .thenAcceptAsync(path ->
            {
                if (path == null)
                {
                    Scarlet.LOG.warn("TTS({}): Provider returned null path, audio generation may have failed", marker);
                    return;
                }
                if (this.eventListener.getTtsUseDefaultAudioDevice())
                {
                    // Route to system default audio output instead of Discord.
                    // playOnSystemAudio blocks until the clip finishes, so the
                    // single-threaded playbackExecutor guarantees clips play serially.
                    playOnSystemAudio(marker, path.toFile(), this.activeClip);
                }
                else
                {
                    boolean submitted = this.discord.submitAudio(path.toFile());
                    Scarlet.LOG.info("TTS({}): Audio submitted to Discord, success={}, file={}", marker, submitted, path);
                    // NOTE: The .wav file is intentionally kept on disk so that Discord and other
                    // consumers have time to finish reading/streaming it. Previously, Files.deleteIfExists(path)
                    // was called here immediately after submitAudio(), causing a race condition where the file
                    // was gone before Discord could access it.
                }
            }, this.playbackExecutor)
            .exceptionally(ex -> {
                Scarlet.LOG.error("TTS({}): Exception during TTS processing", marker, ex);
                return null;
            });
    }

    /**
     * Holds a reference to the {@link Clip} currently playing on the system
     * audio device, or {@code null} when nothing is playing. Used by
     * {@link #skip()} to stop the active clip mid-playback.
     */
    private final java.util.concurrent.atomic.AtomicReference<Clip> activeClip =
        new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * Skips the clip that is currently playing on the system audio device.
     * The {@code playbackExecutor} will immediately move on to the next queued
     * item (if any). If TTS is routed to Discord instead, this is a no-op —
     * Discord manages its own audio stream.
     *
     * @return true if a clip was actually stopped, false if nothing was playing
     */
    public boolean skip()
    {
        Clip clip = this.activeClip.getAndSet(null);
        if (clip != null && clip.isRunning())
        {
            clip.stop(); // fires STOP event → releases semaphore → executor continues
            Scarlet.LOG.info("TTS: Skipped active clip");
            return true;
        }
        Scarlet.LOG.debug("TTS: skip() called but no clip was active");
        return false;
    }

    /**
     * Plays a WAV file through the system default audio output device using javax.sound.sampled.
     * Blocks the calling thread until playback is complete, so the serial playbackExecutor
     * prevents any two clips from overlapping.
     */
    private static void playOnSystemAudio(String marker, File file,
        java.util.concurrent.atomic.AtomicReference<Clip> activeClip)
    {
        Scarlet.LOG.info("TTS({}): Playing on system audio device: {}", marker, file);
        try
        {
            // Open the AudioInputStream first, then open the Clip with it.
            // Clip.open() fully buffers the audio into memory, so the stream
            // can be safely closed immediately afterwards.
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            try
            {
                clip.open(ais);
            }
            finally
            {
                // Safe to close now — Clip has already copied the data
                ais.close();
            }

            // Use a Semaphore to block until the STOP event fires, ensuring
            // this method does not return until the clip has finished playing.
            Semaphore done = new Semaphore(0);
            clip.addLineListener(event ->
            {
                if (event.getType() == LineEvent.Type.STOP)
                {
                    activeClip.compareAndSet(clip, null); // clear only if still ours
                    clip.close();
                    Scarlet.LOG.info("TTS({}): System audio playback complete", marker);
                    done.release();
                }
            });
            activeClip.set(clip);
            clip.start();

            // Block until playback finishes (or give up after a generous timeout).
            long timeoutSeconds = Math.max(30L, (clip.getMicrosecondLength() / 1_000_000L) + 10L);
            if (!done.tryAcquire(timeoutSeconds, TimeUnit.SECONDS))
            {
                Scarlet.LOG.warn("TTS({}): Playback timed out after {}s, forcing clip close", marker, timeoutSeconds);
                clip.close();
            }
        }
        catch (Exception ex)
        {
            Scarlet.LOG.error("TTS({}): Exception playing on system audio device", marker, ex);
        }
    }

    @Override
    public void close()
    {
        this.provider.close();
        this.playbackExecutor.shutdown();
        try
        {
            if (!this.playbackExecutor.awaitTermination(5, TimeUnit.SECONDS))
                this.playbackExecutor.shutdownNow();
        }
        catch (InterruptedException ex)
        {
            this.playbackExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}