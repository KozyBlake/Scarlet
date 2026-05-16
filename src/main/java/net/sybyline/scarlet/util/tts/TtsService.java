package net.sybyline.scarlet.util.tts;

import java.awt.Component;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import net.sybyline.scarlet.util.Names;

public class TtsService implements Closeable
{
    private static final String MIXED_CHARACTER_ALERT_RESOURCE = "/tts/mixed-character-alert.wav";
    private static final String MIXED_CHARACTER_ALERT_FILE = "mixed-character-alert.wav";

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
        this.parentComponent = parentComponent;
        this.mixedCharacterAlertWav = extractBundledAudio(dir,
            MIXED_CHARACTER_ALERT_RESOURCE,
            MIXED_CHARACTER_ALERT_FILE);
        Scarlet.LOG.info("TTS service initialized with provider: {}", this.provider.getClass().getSimpleName());
    }

    final TtsProvider provider;
    final ScarletEventListener eventListener;
    final ScarletDiscord discord;
    final Component parentComponent;
    final File mixedCharacterAlertWav;

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

    /** Returns the list of TTS voices reported by the active provider. */
    public List<String> getInstalledVoices()
    {
        return this.provider.voices();
    }

    // ── Name sanitisation ──────────────────────────────────────────────────────

    /**
     * Prepares a VRChat display name for safe TTS output.
     *
     * <p>VRChat allows display names containing characters that English TTS
     * engines silently skip or read out character-by-character ("b cyrillic
     * l l cyrillic …"): invisible control/format codepoints, private-use
     * characters, decorative symbols, and — most commonly — letters from
     * non-Latin scripts such as Cyrillic, Greek, CJK, Hangul, Thai, Hebrew,
     * Arabic, Devanagari, etc. Both failure modes leave a moderator with no
     * way to map the audio back to the actual user.
     *
     * <p>Scarlet's primary TTS use case is a moderator wearing a VR headset
     * who cannot glance at the Discord embed while an announcement plays, so
     * the spoken form should stay short and recognizable. Latin-looking
     * confusable names are read as their visual English form first. Mixed
     * script names skip script labels and use the best transliteration Scarlet
     * can make; the separate mixed-character alert tells moderators to check
     * nearby nameplates. Single-script non-Latin names keep a short script
     * prefix because that is often the clearest way to scan the lobby.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "Yamada"}          → {@code "Yamada"} (no prefix needed)</li>
     *   <li>{@code "Просто"}          → {@code "Cyrillic Prosto"}</li>
     *   <li>{@code "中文"}             → {@code "CJK Zhong Wen"}</li>
     *   <li>{@code "Αθήνα"}           → {@code "Greek Athena"}</li>
     *   <li>{@code "Yamada 山田"}      → {@code "Yamada Shan Tian"}</li>
     *   <li>{@code "Просто 中文"}      → {@code "Prosto Zhong Wen"}</li>
     * </ul>
     *
     * <p>Transliteration uses the shared {@link Names#toAscii(String)}
     * pipeline (NFKD + stroke-Latin table + {@code junidecode}). For null,
     * empty, or fully-stripped inputs (e.g. all-emoji handles), the method
     * falls back to {@code "an unnamed user"} so the announcement still
     * reads cleanly.
     *
     * @param displayName raw VRChat display name, may be null
     * @return a TTS-safe representation, never null or empty
     */
    public static String sanitizeName(String displayName)
    {
        String visualAscii = Names.toVisualAscii(displayName);
        if (visualAscii != null)
            return visualAscii;
        String ascii = Names.toAscii(displayName);
        if (ascii == null)
            return "an unnamed user";
        if (Names.hasMixedLetterScripts(displayName))
            return ascii;
        String scripts = Names.describeScripts(displayName);
        return scripts.isEmpty() ? ascii : scripts + " " + ascii;
    }

    public static boolean shouldAlertMixedCharacterName(String displayName)
    {
        return Names.hasMixedLetterScripts(displayName);
    }

    private static File extractBundledAudio(File dir, String resourcePath, String fileName)
    {
        try (InputStream in = TtsService.class.getResourceAsStream(resourcePath))
        {
            if (in == null)
            {
                Scarlet.LOG.warn("Bundled TTS audio resource not found: {}", resourcePath);
                return null;
            }

            File out = new File(dir, fileName);
            Files.createDirectories(out.toPath().getParent());
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Scarlet.LOG.info("Extracted bundled TTS audio resource: {} -> {}", resourcePath, out);
            return out;
        }
        catch (Exception ex)
        {
            Scarlet.LOG.warn("Could not extract bundled TTS audio resource {}: {}",
                resourcePath, ex.getMessage());
            return null;
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

    public CompletableFuture<Void> submitMixedCharacterNameJoinAlert(String marker)
    {
        return this.submitAlertThenSpeak(marker,
            "Mixed-letter name joined. Check nameplates.");
    }

    private CompletableFuture<Void> submitAlertThenSpeak(String marker, String text)
    {
        if (this.paused)
        {
            Scarlet.LOG.debug("TTS({}): Dropped mixed-character alert - TTS is paused", marker);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Path> speech = this.synthesize(marker, text);
        return CompletableFuture.runAsync(() ->
        {
            this.playPreparedAudio(marker, this.mixedCharacterAlertWav);
            try
            {
                Path path = speech.get();
                if (path == null)
                {
                    Scarlet.LOG.warn("TTS({}): Provider returned null path, audio generation may have failed", marker);
                    return;
                }
                this.playPreparedAudio(marker, path.toFile());
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                Scarlet.LOG.warn("TTS({}): Interrupted while waiting for mixed-character alert speech", marker);
            }
            catch (Exception ex)
            {
                Scarlet.LOG.error("TTS({}): Exception during mixed-character alert processing", marker, ex);
            }
        }, this.playbackExecutor);
    }

    public CompletableFuture<Void> submit(String marker, String text)
    {
        if (this.paused)
        {
            Scarlet.LOG.debug("TTS({}): Dropped — TTS is paused", marker);
            return CompletableFuture.completedFuture(null);
        }
        return this.synthesize(marker, text)
            .thenAcceptAsync(path ->
            {
                if (path == null)
                {
                    Scarlet.LOG.warn("TTS({}): Provider returned null path, audio generation may have failed", marker);
                    return;
                }
                this.playPreparedAudio(marker, path.toFile());
                // NOTE: The .wav file is intentionally kept on disk so that Discord and other
                // consumers have time to finish reading/streaming it. Previously, Files.deleteIfExists(path)
                // was called here immediately after submitAudio(), causing a race condition where the file
                // was gone before Discord could access it.
            }, this.playbackExecutor)
            .exceptionally(ex -> {
                Scarlet.LOG.error("TTS({}): Exception during TTS processing", marker, ex);
                return null;
            });
    }

    private CompletableFuture<Path> synthesize(String marker, String text)
    {
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        final String selectedVoice = this.eventListener.getTtsVoiceName();
        Scarlet.LOG.info("TTS: Submitting text '{}' with voice '{}' for marker {}",
            text, selectedVoice, marker);
        final String finalText = text;
        return this.provider
            .speak(finalText, selectedVoice, 1.0F, 1.0F)
            .thenCompose(path ->
            {
                // A null path means the voice failed (e.g. an Online/Natural voice that
                // cannot write to a file stream). Trigger fallback voice selection and retry
                // the same text with that voice so the user's command isn't lost.
                if (path == null)
                {
                    String fallbackVoice = this.eventListener.fallbackTtsVoice(selectedVoice);
                    if (fallbackVoice != null)
                    {
                        Scarlet.LOG.info("TTS({}): Retrying with fallback voice '{}'", marker, fallbackVoice);
                        return this.provider.speak(finalText, fallbackVoice, 1.0F, 1.0F);
                    }
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(path);
            });
    }

    /**
     * Holds a reference to the {@link Clip} currently playing on the system
     * audio device, or {@code null} when nothing is playing. Used by
     * {@link #skip()} to stop the active clip mid-playback.
     */
    private final java.util.concurrent.atomic.AtomicReference<Clip> activeClip =
        new java.util.concurrent.atomic.AtomicReference<>(null);

    private void playPreparedAudio(String marker, File file)
    {
        if (file == null || !file.isFile())
        {
            Scarlet.LOG.warn("TTS({}): Audio file unavailable: {}", marker, file);
            return;
        }
        if (this.eventListener != null && this.eventListener.getTtsUseDefaultAudioDevice())
        {
            playOnSystemAudio(marker, file, this.activeClip);
        }
        else if (this.discord != null)
        {
            boolean submitted = this.discord.submitAudio(file);
            Scarlet.LOG.info("TTS({}): Audio submitted to Discord, success={}, file={}", marker, submitted, file);
        }
        else
        {
            Scarlet.LOG.warn("TTS({}): No audio output route available for {}", marker, file);
        }
    }

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
