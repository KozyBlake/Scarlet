package net.sybyline.scarlet.util.tts;

import java.awt.Component;
import java.io.Closeable;
import java.io.File;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.swing.SwingUtilities;

import net.sybyline.scarlet.Features;
import net.sybyline.scarlet.Scarlet;
import net.sybyline.scarlet.ScarletDiscord;
import net.sybyline.scarlet.ScarletEventListener;
import net.sybyline.scarlet.util.rvc.RvcConfig;
import net.sybyline.scarlet.util.rvc.RvcService;
import net.sybyline.scarlet.util.rvc.RvcStatus;

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
        this.parentComponent = parentComponent;

        // ── RVC dependency install flow ─────────────────────────────────────
        // Mirror the TtsPackageInstallDialogs pattern: check deps first, prompt
        // the user for consent if anything is missing, then install via pip.
        // This must run before RvcService.initialize() so the status check finds
        // all packages in place.
        //
        // Skipped entirely in the "lite" edition (Features.RVC_ENABLED == false):
        // that build ships without the Python bridge resource, and running this
        // flow would just prompt the user to install torch/rvc-python for a
        // feature that can't be exercised.  See pom.xml "lite" profile.
        if (Features.RVC_ENABLED)
        {
        Scarlet.LOG.info("Checking RVC Python dependencies...");
        try
        {
            net.sybyline.scarlet.util.rvc.RvcInstallDialogs rvcDialogs =
                new net.sybyline.scarlet.util.rvc.RvcInstallDialogs(
                    parentComponent,
                    net.sybyline.scarlet.util.rvc.RvcService.getResourcePath("rvc_bridge.py"),
                    net.sybyline.scarlet.util.rvc.RvcService.getPythonCommandArgs()
                );

            // queryStatus() is cheap (runs --status in <1 s); only show the
            // full install flow when deps are actually missing.
            net.sybyline.scarlet.util.rvc.RvcStatus preStatus = rvcDialogs.queryStatus();
            if (preStatus == null || !preStatus.isRvcCompatible())
            {
                Scarlet.LOG.info("RVC deps missing — starting install flow");
                net.sybyline.scarlet.util.rvc.RvcInstallDialogs.InstallDialogResult rvcResult =
                    rvcDialogs.showInstallFlow();
                switch (rvcResult)
                {
                    case INSTALL_APPROVED_SUCCESS:
                        Scarlet.LOG.info("RVC dependencies installed successfully");
                        break;
                    case ALREADY_INSTALLED:
                        Scarlet.LOG.info("RVC dependencies already present");
                        break;
                    case INSTALL_APPROVED_FAILED:
                        Scarlet.LOG.warn("RVC dependency installation failed; RVC will be unavailable");
                        break;
                    case INSTALL_DECLINED:
                        Scarlet.LOG.info("User declined RVC installation; RVC will be unavailable");
                        break;
                    case HEADLESS_MODE:
                        Scarlet.LOG.info("Headless mode: RVC install attempted without UI");
                        break;
                    default:
                        break;
                }
            }
            else
            {
                Scarlet.LOG.info("RVC dependencies already installed — skipping install flow");
            }
        }
        catch (Exception rvcEx)
        {
            Scarlet.LOG.warn("RVC dependency check/install failed (non-fatal): {}", rvcEx.getMessage());
        }

        this.rvcService = new RvcService(dir);
        this.rvcService.initialize().thenAccept(status -> {
            if (status.isRvcCompatible())
            {
                Scarlet.LOG.info("RVC service initialized: GPU={}, Models={}",
                    status.isGpuAvailable() ? status.gpu.name : "CPU",
                    status.modelsAvailable != null ? status.modelsAvailable.size() : 0);
            }
            else
            {
                Scarlet.LOG.info("RVC service not available: {}",
                    status.dependenciesMissing != null ? status.dependenciesMissing : "Unknown reason");
                maybePromptRvcRepair("RVC is installed but not currently usable.");
            }
        });
        }
        else
        {
            // Lite edition: leave rvcService null so every consumer's
            // existing null-guards (isRvcAvailable, getRvcService,
            // getRvcModelsDir, ...) report "unavailable" without ever
            // touching the Python bridge or firing the install flow.
            this.rvcService = null;
            Scarlet.LOG.info("RVC subsystem disabled by feature flag (lite edition)");
        }
        Scarlet.LOG.info("TTS service initialized with provider: {}", this.provider.getClass().getSimpleName());
    }

    final TtsProvider provider;
    final ScarletEventListener eventListener;
    final ScarletDiscord discord;
    final Component parentComponent;
    final RvcService rvcService;
    private volatile RvcConfig rvcConfig = new RvcConfig();
    private final AtomicBoolean rvcRepairPrompted = new AtomicBoolean(false);

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

    // ── RVC virtual voices ────────────────────────────────────────────────────
    //
    // Each .pth model in the RVC models directory is surfaced as its own entry
    // in the voice-picker as "RVC: <model-path>".  Selecting one of those
    // entries makes submit() synthesise with a neutral base TTS voice and
    // immediately pipe the audio through RVC with that specific model, so the
    // user experiences picking "an RVC voice" from the same list they already
    // use for picking TTS voices — no separate UI for model selection.

    /** Prefix used for virtual RVC voices in {@link #getInstalledVoices()}. */
    public static final String RVC_VOICE_PREFIX = "RVC: ";

    /**
     * @return {@code true} iff {@code voiceName} is one of the synthetic
     *         {@code "RVC: ..."} entries produced by this service.
     */
    public static boolean isRvcVirtualVoice(String voiceName)
    {
        return voiceName != null && voiceName.startsWith(RVC_VOICE_PREFIX);
    }

    /**
     * Strip the {@link #RVC_VOICE_PREFIX} and return the underlying model
     * path.  Returns {@code null} when {@code voiceName} is not a virtual
     * RVC voice.
     */
    public static String rvcModelFromVirtualVoice(String voiceName)
    {
        if (!isRvcVirtualVoice(voiceName))
            return null;
        String model = voiceName.substring(RVC_VOICE_PREFIX.length()).trim();
        return model.isEmpty() ? null : model;
    }

    /**
     * List of available RVC models formatted as virtual voice names.
     * Empty when RVC is unavailable or no models are installed.
     */
    public List<String> getRvcVirtualVoices()
    {
        if (this.rvcService == null || !this.rvcService.isAvailable())
            return java.util.Collections.emptyList();
        List<String> models = this.rvcService.getAvailableModels();
        if (models == null || models.isEmpty())
            return java.util.Collections.emptyList();
        List<String> out = new ArrayList<>(models.size());
        for (String m : models)
            out.add(RVC_VOICE_PREFIX + m);
        return out;
    }

    /**
     * Return a reasonable non-RVC voice to use as the base voice for
     * virtual RVC voices.  Picks the first real (non-RVC, non-empty) voice
     * from the provider.  Returns {@code null} if no real voices exist
     * (extremely unusual — the app has bigger problems in that case).
     */
    public String getNeutralBaseVoice()
    {
        List<String> voices = this.provider.voices();
        if (voices == null)
            return null;
        for (String v : voices)
        {
            if (v == null || v.trim().isEmpty())
                continue;
            if (isRvcVirtualVoice(v))   // defensive; provider shouldn't return these
                continue;
            return v;
        }
        return null;
    }

    /**
     * Returns the full voice list shown to the user: real TTS voices from
     * the provider plus one virtual {@code "RVC: <model>"} entry per
     * installed RVC model.  Real voices come first so {@link #getNeutralBaseVoice()}
     * stays deterministic and the default selection is always a real voice.
     */
    public List<String> getInstalledVoices()
    {
        List<String> base = this.provider.voices();
        List<String> rvc  = getRvcVirtualVoices();
        if (rvc.isEmpty())
            return base;
        // Defensive copy: some providers return an unmodifiable or cached list.
        List<String> combined = new ArrayList<>((base != null ? base.size() : 0) + rvc.size());
        if (base != null)
            combined.addAll(base);
        combined.addAll(rvc);
        return combined;
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
        final String selectedVoice = this.eventListener.getTtsVoiceName();

        // ── Virtual RVC voice resolution ────────────────────────────────────
        //
        // If the user picked an "RVC: <model>" entry we synthesise with a
        // neutral base voice and route the output through that specific RVC
        // model regardless of the global RvcConfig's enabled flag.  This is
        // what makes the user's mental model ("pick a voice, hear that voice")
        // match reality — the RVC step is part of the voice, not a toggle.
        //
        // When the selected voice is an RVC virtual voice but RVC itself is
        // unavailable (deps not installed / bridge missing), fall back to the
        // base voice alone and log a warning, so the user still hears
        // something instead of getting silence.
        final String rvcModelOverride;
        final String synthVoice;
        if (isRvcVirtualVoice(selectedVoice))
        {
            String model = rvcModelFromVirtualVoice(selectedVoice);
            String base  = getNeutralBaseVoice();
            if (base == null)
            {
                Scarlet.LOG.warn("TTS({}): Virtual RVC voice '{}' selected but no real "
                               + "base TTS voice is available; dropping", marker, selectedVoice);
                return CompletableFuture.completedFuture(null);
            }
            if (this.rvcService != null && this.rvcService.isAvailable() && model != null)
            {
                Scarlet.LOG.info("TTS: Virtual RVC voice '{}' → base voice '{}' + model '{}'",
                    selectedVoice, base, model);
                rvcModelOverride = model;
            }
            else
            {
                Scarlet.LOG.warn("TTS: Virtual RVC voice '{}' selected but RVC is "
                               + "unavailable; synthesising with base voice '{}' only",
                    selectedVoice, base);
                rvcModelOverride = null;
            }
            synthVoice = base;
        }
        else
        {
            rvcModelOverride = null;
            synthVoice       = selectedVoice;
        }

        Scarlet.LOG.info("TTS: Submitting text '{}' with voice '{}' for marker {}",
            text, selectedVoice, marker);
        final String finalText = text;
        final RvcConfig currentRvcConfig = this.rvcConfig; // Capture current config
        return this.provider
            .speak(finalText, synthVoice, 1.0F, 1.0F)
            .thenCompose(path ->
            {
                // A null path means the voice failed (e.g. an Online/Natural voice that
                // cannot write to a file stream). Trigger fallback voice selection and retry
                // the same text with that voice so the user's command isn't lost.
                if (path == null)
                {
                    // Note: we pass the selected (virtual or real) voice to the fallback
                    // selector so the UI's "current voice" gets updated to a working one
                    // instead of silently flipping to the base-of-virtual voice.
                    String fallbackVoice = this.eventListener.fallbackTtsVoice(selectedVoice);
                    if (fallbackVoice != null)
                    {
                        Scarlet.LOG.info("TTS({}): Retrying with fallback voice '{}'", marker, fallbackVoice);
                        // The fallback could itself be a virtual RVC voice, but the
                        // cleanest behaviour is to synth it directly without another
                        // level of virtual-voice resolution — fallback means "just
                        // make some sound", not "re-run the whole resolution dance".
                        String fallbackSynth = isRvcVirtualVoice(fallbackVoice)
                            ? getNeutralBaseVoice()
                            : fallbackVoice;
                        if (fallbackSynth == null)
                            return CompletableFuture.completedFuture(null);
                        return this.provider.speak(finalText, fallbackSynth, 1.0F, 1.0F);
                    }
                    // No fallback available — complete with null, exceptionally handler logs it
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(path);
            })
            .thenCompose(path -> {
                // ── RVC post-processing step ────────────────────────────────
                //
                // Two paths converge here:
                //   (a) Virtual RVC voice was picked → rvcModelOverride != null.
                //       Always run convertWithModel() (bypasses global enabled flag).
                //   (b) Global "RVC enabled" session post-processing is on.
                //       Run convert() using the current config.
                if (path == null)
                    return CompletableFuture.completedFuture(null);

                final boolean rvcAvailable = this.rvcService != null && this.rvcService.isAvailable();
                if (!rvcAvailable)
                    return CompletableFuture.completedFuture(path);

                final boolean useOverride = rvcModelOverride != null;
                if (!useOverride && !currentRvcConfig.enabled)
                    return CompletableFuture.completedFuture(path);
                if (!useOverride && (currentRvcConfig.modelPath == null
                    || currentRvcConfig.modelPath.trim().isEmpty()))
                {
                    Scarlet.LOG.warn("TTS({}): Global RVC is enabled but no RVC model is selected; "
                        + "playing unmodified TTS audio", marker);
                    return CompletableFuture.completedFuture(path);
                }

                final String modelForLog = useOverride ? rvcModelOverride : currentRvcConfig.modelPath;
                Scarlet.LOG.info("TTS({}): Applying RVC voice conversion with model '{}' ({})",
                    marker, modelForLog,
                    useOverride ? "virtual-voice override" : "global RVC config");

                // Create output path for RVC-processed audio
                Path rvcOutput = path.resolveSibling(
                    path.getFileName().toString().replace(".wav", "_rvc.wav"));

                CompletableFuture<Path> rvcFuture = useOverride
                    ? this.rvcService.convertWithModel(path, rvcOutput, rvcModelOverride)
                    : this.rvcService.convert(path, rvcOutput);

                return rvcFuture
                    .thenApply(rvcPath -> {
                        if (rvcPath != null && !rvcPath.equals(path))
                        {
                            Scarlet.LOG.info("TTS({}): RVC conversion complete: {}", marker, rvcPath);
                            return rvcPath;
                        }
                        // RVC failed or returned original, use the original path
                        return path;
                    })
                    .exceptionally(ex -> {
                        Scarlet.LOG.warn("TTS({}): RVC conversion failed, using original audio: {}",
                            marker, ex.getMessage());
                        maybePromptRvcRepair("RVC conversion failed while processing TTS.");
                        return path;
                    });
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

    // -------------------------------------------------------------------------
    // RVC Configuration
    // -------------------------------------------------------------------------

    /**
     * Get the current RVC configuration.
     */
    public RvcConfig getRvcConfig()
    {
        return this.rvcConfig;
    }

    /**
     * Set the RVC configuration for voice conversion.  In the lite
     * edition {@code rvcService} is null — we still cache the config
     * so the getter round-trips the same object, but nothing else
     * happens.
     */
    public void setRvcConfig(RvcConfig config)
    {
        this.rvcConfig = config != null ? config : new RvcConfig();
        if (this.rvcService != null)
            this.rvcService.setConfig(this.rvcConfig);
    }

    /**
     * Check if RVC is available on this system.  Always false in the
     * lite edition.
     */
    public boolean isRvcAvailable()
    {
        return this.rvcService != null && this.rvcService.isAvailable();
    }

    /**
     * Get the RVC service status.  {@code null} in the lite edition.
     */
    public RvcStatus getRvcStatus()
    {
        return this.rvcService != null ? this.rvcService.getStatus() : null;
    }

    /**
     * Get the directory where RVC models should be placed.
     * {@code null} in the lite edition.
     */
    public File getRvcModelsDir()
    {
        if (this.rvcService == null)
            return null;
        Path p = this.rvcService.getModelsDir();
        return p != null ? p.toFile() : null;
    }

    /**
     * Get the underlying RVC service. Exposed so UI helpers (e.g. the
     * model manager dialog) can refresh the bridge status after adding
     * or removing model files.
     *
     * @return the {@link RvcService} instance, or {@code null} in the
     *         lite edition where the subsystem is disabled.
     */
    public RvcService getRvcService()
    {
        return this.rvcService;
    }

    /**
     * Get list of available RVC models.  Empty in the lite edition.
     */
    public List<String> getRvcModels()
    {
        return this.rvcService != null
            ? this.rvcService.getAvailableModels()
            : new ArrayList<>();
    }

    private void maybePromptRvcRepair(String reason)
    {
        if (this.parentComponent == null || !this.rvcRepairPrompted.compareAndSet(false, true))
            return;

        SwingUtilities.invokeLater(() ->
        {
            try
            {
                int choice = javax.swing.JOptionPane.showConfirmDialog(
                    this.parentComponent,
                    reason + "\n\nReinstall or repair the RVC Python dependencies now?",
                    "RVC Repair",
                    javax.swing.JOptionPane.YES_NO_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE
                );
                if (choice == javax.swing.JOptionPane.YES_OPTION)
                {
                    net.sybyline.scarlet.util.rvc.RvcInstallDialogs.InstallDialogResult result =
                        this.rvcService.installDependencies(this.parentComponent);
                    if (result == net.sybyline.scarlet.util.rvc.RvcInstallDialogs.InstallDialogResult.INSTALL_APPROVED_SUCCESS
                        || result == net.sybyline.scarlet.util.rvc.RvcInstallDialogs.InstallDialogResult.ALREADY_INSTALLED)
                    {
                        this.rvcService.refreshStatus().thenAccept(status ->
                        {
                            Scarlet.LOG.info("RVC repair refresh complete: compatible={}, models={}",
                                status != null && status.isRvcCompatible(),
                                status != null && status.modelsAvailable != null ? status.modelsAvailable.size() : 0);
                        });
                    }
                    this.rvcRepairPrompted.set(false);
                }
                else
                {
                    this.rvcRepairPrompted.set(false);
                }
            }
            catch (Exception ex)
            {
                this.rvcRepairPrompted.set(false);
                Scarlet.LOG.warn("Could not show RVC repair prompt: {}", ex.getMessage());
            }
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