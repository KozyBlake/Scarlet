package net.sybyline.scarlet.util.rvc;

import java.awt.Component;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sybyline.scarlet.util.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.sybyline.scarlet.Features;
import net.sybyline.scarlet.Scarlet;

/**
 * RVC (Retrieval-based Voice Conversion) Service for Scarlet TTS.
 *
 * <p>This service provides voice conversion capabilities by integrating RVC
 * models with the TTS pipeline.  It supports both GPU (CUDA) and CPU
 * inference, with automatic hardware detection and graceful fallback.</p>
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Automatic GPU/CPU detection and selection</li>
 *   <li>Dependency auto-installer via {@link RvcInstallDialogs}</li>
 *   <li>Model management and loading</li>
 *   <li>Async audio conversion with configurable timeout</li>
 *   <li>Fallback to passthrough when RVC is unavailable</li>
 * </ul>
 *
 * <h2>Hardware Requirements:</h2>
 * <ul>
 *   <li><b>Recommended:</b> NVIDIA GPU with 4 GB+ VRAM (GTX 1660 Ti or better)</li>
 *   <li><b>Minimum:</b> CPU-only mode (10–100× slower than GPU)</li>
 *   <li>Python 3.9+ with torch, torchaudio, rvc-python</li>
 * </ul>
 *
 * @see RvcConfig
 * @see RvcStatus
 * @see RvcInstallDialogs
 */
public class RvcService implements AutoCloseable
{
    private static final Logger LOG  = LoggerFactory.getLogger("Scarlet/RVC");
    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();

    // -------------------------------------------------------------------------
    // Static helpers — Python command and bridge script path
    // -------------------------------------------------------------------------

    /** Minimum Python version supported by the RVC bridge. */
    public static final int PYTHON_MIN_MAJOR = 3;
    public static final int PYTHON_MIN_MINOR = 9;

    /**
     * Maximum Python version supported by the RVC bridge.
     *
     * <p>{@code rvc-python} transitively pins {@code numpy<=1.25.3} and
     * {@code fairseq==0.12.2}.  Neither publishes wheels for Python 3.12+,
     * and {@code numpy 1.25.2}'s sdist fails to build on 3.12/3.13 because
     * {@code setuptools}' {@code pkg_resources} references
     * {@code pkgutil.ImpImporter}, which was removed in 3.12.  Users on
     * 3.12/3.13 see:
     * <pre>
     *   AttributeError: module 'pkgutil' has no attribute 'ImpImporter'
     * </pre>
     * during {@code pip install rvc-python}.  Cap at 3.11 until upstream
     * updates its pins.  Keep in sync with {@code MAX_PYTHON} in
     * {@code rvc_bridge.py}.</p>
     */
    public static final int PYTHON_MAX_MAJOR = 3;
    public static final int PYTHON_MAX_MINOR = 11;

    /**
     * "Python 3.11.4" → captures 3, 11, 4.  Accepts CPython and PyPy
     * version banners, which both start with "Python X.Y.Z".
     */
    private static final Pattern PYTHON_VERSION = Pattern.compile(
        "Python\\s+(\\d+)\\.(\\d+)(?:\\.(\\d+))?"
    );

    /**
     * Candidate Python launchers, tried in order.
     *
     * <p>On Windows we ask the {@code py} launcher for specific <em>supported</em>
     * minor versions first ({@code -3.11} down to {@code -3.9}) before falling
     * back to {@code py -3}, because {@code py -3} on a box that has 3.13
     * installed will select 3.13 — and rvc-python's dependency chain (numpy
     * 1.25.x, fairseq 0.12.2) cannot build on 3.12+.  Selecting a
     * {@link #PYTHON_MAX_MAJOR}.{@link #PYTHON_MAX_MINOR}-or-older interpreter
     * up front prevents the pip failure described on
     * {@link #PYTHON_MAX_MINOR}.</p>
     *
     * <p>On POSIX we try {@code python3.11}…{@code python3.9} before the bare
     * {@code python3}/{@code python} for the same reason.</p>
     */
    private static List<String[]> pythonCandidates()
    {
        List<String[]> cands = new ArrayList<>();
        if (Platform.CURRENT.isNT())
        {
            // Prefer specific supported versions via the py launcher,
            // newest-supported first.
            for (int minor = PYTHON_MAX_MINOR; minor >= PYTHON_MIN_MINOR; minor--)
                cands.add(new String[]{ "py", "-" + PYTHON_MAX_MAJOR + "." + minor });
            // Generic launcher — may pick a too-new Python, probe will detect that.
            cands.add(new String[]{ "py", "-3" });
            // Direct executables as last resorts.
            cands.add(new String[]{ "python" });
            cands.add(new String[]{ "python3" });
        }
        else
        {
            // Specific supported versions first, newest-supported first.
            for (int minor = PYTHON_MAX_MINOR; minor >= PYTHON_MIN_MINOR; minor--)
                cands.add(new String[]{ "python" + PYTHON_MAX_MAJOR + "." + minor });
            cands.add(new String[]{ "python3" });
            cands.add(new String[]{ "python" });
        }
        return cands;
    }

    /**
     * Cached result so we only probe the filesystem once per run.
     * {@code null} until {@link #getPythonCommandArgs()} has been called.
     */
    private static volatile List<String> cachedPythonArgs = null;

    /**
     * Detect a working Python {@value #PYTHON_MIN_MAJOR}.{@value #PYTHON_MIN_MINOR}+
     * executable on the current system and return it as a list of command
     * tokens suitable for {@link ProcessBuilder}.
     *
     * <p>On Windows the tokens can be {@code ["py", "-3"]} — returning a
     * list (instead of a single string) is what lets that work; otherwise
     * {@code ProcessBuilder} would try to exec a binary literally called
     * {@code "py -3"}.</p>
     *
     * <p>Each candidate is run with {@code --version}, its output parsed,
     * and the first one that exits {@code 0} AND reports a version
     * {@literal >=} the minimum is returned.  If none qualifies we fall
     * back to a platform-appropriate default so callers still get something
     * to shell out to — the bridge script will then report a clear
     * "Python too old" error instead of crashing deep in an import.</p>
     *
     * <p>Exceptions from individual probes are logged at debug level rather
     * than silently swallowed — the previous behaviour made it impossible
     * to diagnose why a perfectly good install was being missed.</p>
     */
    public static List<String> getPythonCommandArgs()
    {
        List<String> cached = cachedPythonArgs;
        if (cached != null)
            return cached;

        synchronized (RvcService.class)
        {
            if (cachedPythonArgs != null)
                return cachedPythonArgs;

            // Track best-effort fallbacks for when no in-range Python exists.
            // Preference order when falling back: too-new before too-old, because
            // a too-new interpreter is more likely to at least run --status
            // cleanly (so the user sees a rich error), while a too-old one
            // may fail to import things the bridge uses.
            String[] tooNew = null;     String tooNewVersion = null;
            String[] tooOld = null;     String tooOldVersion = null;

            for (String[] cmd : pythonCandidates())
            {
                String joined = String.join(" ", cmd);
                try
                {
                    List<String> pb = new ArrayList<>(Arrays.asList(cmd));
                    pb.add("--version");

                    Process p = new ProcessBuilder(pb).redirectErrorStream(true).start();

                    StringBuilder out = new StringBuilder();
                    try (BufferedReader br =
                             new BufferedReader(new InputStreamReader(p.getInputStream())))
                    {
                        String line;
                        while ((line = br.readLine()) != null)
                            out.append(line).append('\n');
                    }

                    boolean finished = p.waitFor(5, TimeUnit.SECONDS);
                    if (!finished)
                    {
                        p.destroyForcibly();
                        LOG.debug("Python probe '{}' timed out", joined);
                        continue;
                    }
                    if (p.exitValue() != 0)
                    {
                        LOG.debug("Python probe '{}' exited with {}", joined, p.exitValue());
                        continue;
                    }

                    Matcher m = PYTHON_VERSION.matcher(out.toString());
                    if (!m.find())
                    {
                        LOG.debug("Python probe '{}' produced unrecognised version: {}",
                                  joined, out.toString().trim());
                        continue;
                    }

                    int major = Integer.parseInt(m.group(1));
                    int minor = Integer.parseInt(m.group(2));
                    String versionStr = m.group(0).replace("Python ", "");

                    // Encode version as M*100 + m for a simple in-range compare.
                    int code    = major * 100 + minor;
                    int minCode = PYTHON_MIN_MAJOR * 100 + PYTHON_MIN_MINOR;
                    int maxCode = PYTHON_MAX_MAJOR * 100 + PYTHON_MAX_MINOR;

                    if (code >= minCode && code <= maxCode)
                    {
                        LOG.info("Using Python '{}' (version {}) — within "
                               + "supported range {}.{}\u2013{}.{}",
                            joined, versionStr,
                            PYTHON_MIN_MAJOR, PYTHON_MIN_MINOR,
                            PYTHON_MAX_MAJOR, PYTHON_MAX_MINOR);
                        cachedPythonArgs = Collections.unmodifiableList(
                            new ArrayList<>(Arrays.asList(cmd)));
                        return cachedPythonArgs;
                    }

                    if (code > maxCode)
                    {
                        if (tooNew == null)
                        {
                            tooNew = cmd;
                            tooNewVersion = versionStr;
                        }
                        LOG.debug("Python probe '{}' found version {} — ABOVE max {}.{}",
                                  joined, versionStr,
                                  PYTHON_MAX_MAJOR, PYTHON_MAX_MINOR);
                    }
                    else // code < minCode
                    {
                        if (tooOld == null)
                        {
                            tooOld = cmd;
                            tooOldVersion = versionStr;
                        }
                        LOG.debug("Python probe '{}' found version {} — BELOW min {}.{}",
                                  joined, versionStr,
                                  PYTHON_MIN_MAJOR, PYTHON_MIN_MINOR);
                    }
                }
                catch (IOException ex)
                {
                    LOG.debug("Python probe '{}' not found: {}", joined, ex.getMessage());
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    LOG.debug("Python probe '{}' interrupted", joined);
                    break;
                }
                catch (Exception ex)
                {
                    LOG.debug("Python probe '{}' failed: {}", joined, ex.getMessage());
                }
            }

            if (tooNew != null)
            {
                LOG.warn("No Python in supported range {}.{}\u2013{}.{} found; "
                       + "falling back to '{}' (version {}).  rvc-python cannot be "
                       + "installed on this version because its numpy pin has no "
                       + "wheels for 3.12+ — please install Python {}.{} and retry.",
                    PYTHON_MIN_MAJOR, PYTHON_MIN_MINOR,
                    PYTHON_MAX_MAJOR, PYTHON_MAX_MINOR,
                    String.join(" ", tooNew), tooNewVersion,
                    PYTHON_MAX_MAJOR, PYTHON_MAX_MINOR);
                cachedPythonArgs = Collections.unmodifiableList(
                    new ArrayList<>(Arrays.asList(tooNew)));
                return cachedPythonArgs;
            }

            if (tooOld != null)
            {
                LOG.warn("No Python {}.{}+ found; falling back to '{}' (version {}) — "
                       + "RVC status check will report this as incompatible.",
                    PYTHON_MIN_MAJOR, PYTHON_MIN_MINOR,
                    String.join(" ", tooOld), tooOldVersion);
                cachedPythonArgs = Collections.unmodifiableList(
                    new ArrayList<>(Arrays.asList(tooOld)));
                return cachedPythonArgs;
            }

            // Nothing at all found.  Return a platform-appropriate default
            // so the bridge script launch produces a clear error the user
            // can act on, rather than "python3: command not found" on a
            // Windows box.
            String[] fallback = Platform.CURRENT.isNT()
                ? new String[]{ "py", "-3" }
                : new String[]{ "python3" };
            LOG.warn("No Python interpreter found on this system; falling back to '{}'.  "
                   + "RVC will not work until Python {}.{}\u2013{}.{} is installed.",
                String.join(" ", fallback),
                PYTHON_MIN_MAJOR, PYTHON_MIN_MINOR,
                PYTHON_MAX_MAJOR, PYTHON_MAX_MINOR);
            cachedPythonArgs = Collections.unmodifiableList(
                new ArrayList<>(Arrays.asList(fallback)));
            return cachedPythonArgs;
        }
    }

    /**
     * Back-compat accessor returning the detected Python command as a
     * single whitespace-joined string (e.g. {@code "python3"} or
     * {@code "py -3"}).
     *
     * <p>Do <em>not</em> pass this to {@link ProcessBuilder} on Windows —
     * use {@link #getPythonCommandArgs()} to get proper tokens.  This
     * overload remains for logging, display, and building pip commands
     * for dialog text.</p>
     */
    public static String getPythonCommand()
    {
        return String.join(" ", getPythonCommandArgs());
    }

    /** Clear the detection cache.  Useful for tests / after install. */
    public static void resetPythonCache()
    {
        cachedPythonArgs = null;
    }

    /**
     * Resolve the path to {@code rvc_bridge.py}.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>App-data directory: {@code <Scarlet.dir>/tts/rvc/rvc_bridge.py}</li>
     *   <li>Beside the running JAR: {@code <jar-dir>/rvc/rvc_bridge.py}</li>
     * </ol>
     *
     * If neither location exists we still return the app-data path so callers
     * can report a meaningful "file not found" error.
     */
    public static Path getResourcePath(String resource)
    {
        Path appDir  = Scarlet.dir.toPath();
        migrateLegacyRvcRoot(appDir);
        Path primary = appDir.resolve("tts").resolve("rvc").resolve(resource);

        // 1. Beside the JAR (portable / developer layout) — this is a
        //    hand-maintained file so treat it as authoritative over the
        //    auto-extracted copy if present.
        try
        {
            Path jarPath = net.sybyline.scarlet.util.MavenDepsLoader.jarPath();
            if (jarPath != null)
            {
                Path candidate = jarPath.getParent().resolve("rvc").resolve(resource);
                if (Files.exists(candidate))
                    return candidate;
            }
        }
        catch (Exception ex)
        {
            LOG.debug("Could not check JAR-relative RVC resource path: {}", ex.getMessage());
        }

        // 2. Classpath extraction (JAR bundle) — always refresh from the
        //    classpath so a Scarlet upgrade (e.g. new CLI flags on the
        //    bridge) is actually picked up by the already-installed
        //    app-data copy.  `extractBundledResource` returns null when
        //    the resource is absent from the classpath (portable/dev
        //    layout where the file only exists beside the JAR), in which
        //    case we fall through to the app-data lookup below.
        Path extracted = extractBundledResource(resource, appDir);
        if (extracted != null)
            return extracted;

        // 3. App-data directory (runtime copy / user-placed file) — only
        //    used when neither the JAR-beside layout nor the classpath
        //    have the resource.
        if (Files.exists(primary))
            return primary;

        // Final fallback — path may not exist yet, caller handles it
        LOG.warn("Could not locate RVC resource: {}", resource);
        return primary;
    }

    /**
     * Extract a bundled classpath resource (inside the JAR under
     * {@code /rvc/<resource>}) to {@code <appDir>/tts/rvc/<resource>} so the
     * Python interpreter can find and execute it.
     *
     * @return the extracted path, or {@code null} on failure.
     */
    private static Path extractBundledResource(String resource, Path appDir)
    {
        String classpathKey = "/rvc/" + resource;
        try (java.io.InputStream in =
                 RvcService.class.getResourceAsStream(classpathKey))
        {
            if (in == null)
                return null;

            migrateLegacyRvcRoot(appDir);
            Path dest = appDir.resolve("tts").resolve("rvc").resolve(resource);
            Files.createDirectories(dest.getParent());
            Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Extracted bundled RVC resource: {} → {}", classpathKey, dest);
            return dest;
        }
        catch (Exception ex)
        {
            LOG.debug("Could not extract bundled RVC resource {}: {}", resource, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Static status check (no instance required)
    // -------------------------------------------------------------------------

    /**
     * Check RVC availability without creating a full service instance.
     * Delegates to {@code rvc_bridge.py --status}.
     */
    public static RvcStatus checkStatus()
    {
        try
        {
            List<String> cmd = new ArrayList<>(getPythonCommandArgs());
            cmd.add(getResourcePath("rvc_bridge.py").toString());
            cmd.add("--status");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(proc.getInputStream())))
            {
                String line;
                while ((line = reader.readLine()) != null)
                    output.append(line).append('\n');
            }

            int exit = proc.waitFor(30, TimeUnit.SECONDS) ? proc.exitValue() : -1;
            if (exit == 0 && output.length() > 0)
                return GSON.fromJson(output.toString(), RvcStatus.class);
        }
        catch (Exception ex)
        {
            LOG.debug("RVC status check failed: {}", ex.getMessage());
        }

        RvcStatus status = new RvcStatus();
        status.rvcCompatible       = false;
        status.dependenciesMissing = Collections.singletonList(
            "Python not found or RVC bridge unavailable");
        return status;
    }

    // -------------------------------------------------------------------------
    // Instance fields
    // -------------------------------------------------------------------------

    private final Path            rvcDir;
    private final Path            modelsDir;
    private final ExecutorService executor;
    private final AtomicBoolean   initialized = new AtomicBoolean(false);
    private final AtomicReference<RvcStatus> status = new AtomicReference<>(null);
    private final Map<String, String>        loadedModels = new ConcurrentHashMap<>();
    private volatile RvcConfig config = new RvcConfig();

    public RvcService(File dataDir)
    {
        this.rvcDir    = dataDir.toPath().resolve("rvc");
        this.modelsDir = this.rvcDir.resolve("models");
        this.executor  = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "RVC-Conversion");
            t.setDaemon(true);
            return t;
        });

        try
        {
            migrateLegacyRvcRoot(dataDir.getParentFile().toPath());
            Files.createDirectories(this.modelsDir);
        }
        catch (IOException ex)
        {
            LOG.warn("Could not create RVC directories", ex);
        }
    }

    private static void migrateLegacyRvcRoot(Path appDir)
    {
        Path legacy = appDir.resolve("rvc");
        Path current = appDir.resolve("tts").resolve("rvc");
        if (!Files.isDirectory(legacy))
            return;

        try
        {
            Files.createDirectories(current);
            try (java.util.stream.Stream<Path> stream = Files.walk(legacy))
            {
                for (Path src : (Iterable<Path>) stream::iterator)
                {
                    Path rel = legacy.relativize(src);
                    Path dest = current.resolve(rel);
                    if (Files.isDirectory(src))
                    {
                        Files.createDirectories(dest);
                    }
                    else
                    {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(legacy))
            {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(path ->
                      {
                          try
                          {
                              Files.deleteIfExists(path);
                          }
                          catch (IOException ex)
                          {
                              LOG.debug("Could not delete legacy RVC path {}: {}", path, ex.getMessage());
                          }
                      });
            }
            LOG.info("Migrated legacy RVC runtime from {} to {}", legacy, current);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to migrate legacy RVC runtime from {} to {}: {}",
                legacy, current, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Dependency installation
    // -------------------------------------------------------------------------

    /**
     * Run the full dependency-install flow, showing Swing dialogs where
     * appropriate (consent → progress → result).
     *
     * <p>Call this <em>before</em> {@link #initialize()} so that
     * {@code initialize()} can find all packages installed.</p>
     *
     * @param parentComponent  Swing parent for dialogs; may be {@code null}.
     * @return the install-flow result
     */
    public RvcInstallDialogs.InstallDialogResult installDependencies(Component parentComponent)
    {
        Path bridgeScript      = getResourcePath("rvc_bridge.py");
        List<String> pythonArgs = getPythonCommandArgs();

        LOG.info("Starting RVC dependency install flow (bridge={}, python={})",
                 bridgeScript, pythonArgs);

        RvcInstallDialogs dialogs = new RvcInstallDialogs(parentComponent, bridgeScript, pythonArgs);
        RvcInstallDialogs.InstallDialogResult result = dialogs.showInstallFlow();

        LOG.info("RVC install flow result: {}", result);
        return result;
    }

    /**
     * Convenience overload — no parent component (headless / background).
     */
    public RvcInstallDialogs.InstallDialogResult installDependencies()
    {
        return installDependencies(null);
    }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Initialize the RVC service and detect hardware compatibility.
     * Returns a {@link CompletableFuture} so the caller can react to the result
     * asynchronously without blocking the UI thread.
     */
    public CompletableFuture<RvcStatus> initialize()
    {
        // Belt-and-suspenders: TtsService already skips RvcService
        // construction when Features.RVC_ENABLED is false, so this
        // path only executes in full-edition builds.  Guarding here
        // too means direct callers (tests, headless scripts) can't
        // accidentally fire the dependency-install flow in a lite
        // build just by instantiating RvcService themselves.
        if (!Features.RVC_ENABLED)
        {
            RvcStatus disabled = new RvcStatus();
            disabled.dependenciesMissing = java.util.Collections.singletonList(
                "RVC disabled by feature flag");
            this.status.set(disabled);
            this.initialized.set(true);
            return CompletableFuture.completedFuture(disabled);
        }
        return CompletableFuture.supplyAsync(() ->
        {
            RvcStatus currentStatus = checkStatus();
            this.status.set(currentStatus);
            this.initialized.set(true);

            if (currentStatus.isRvcCompatible())
            {
                LOG.info("RVC initialized. GPU: {} ({} GB), device: {}",
                    currentStatus.gpu != null ? currentStatus.gpu.name : "unknown",
                    currentStatus.gpu != null ? currentStatus.gpu.memoryGb : 0,
                    currentStatus.gpu != null ? currentStatus.gpu.device : "cpu");
            }
            else
            {
                LOG.warn("RVC not fully compatible. Missing: {}", currentStatus.dependenciesMissing);
            }

            return currentStatus;
        }, this.executor);
    }

    // -------------------------------------------------------------------------
    // Status accessors
    // -------------------------------------------------------------------------

    /** @return Current RVC status, or {@code null} if not yet initialized. */
    public RvcStatus getStatus()
    {
        return this.status.get();
    }

    /**
     * Re-query the bridge for an up-to-date status, in particular to pick up
     * newly-added {@code .pth} models after the UI has copied them into
     * {@link #getModelsDir()}. Runs asynchronously on the conversion executor
     * to avoid blocking the UI thread.
     *
     * @return CompletableFuture resolving to the refreshed status.
     */
    public CompletableFuture<RvcStatus> refreshStatus()
    {
        return CompletableFuture.supplyAsync(() ->
        {
            RvcStatus fresh = checkStatus();
            this.status.set(fresh);
            return fresh;
        }, this.executor);
    }

    /** @return {@code true} if RVC is available and all deps are present. */
    public boolean isAvailable()
    {
        RvcStatus s = this.status.get();
        return s != null && s.isRvcCompatible();
    }

    /** @return {@code true} if GPU acceleration is available. */
    public boolean isGpuAvailable()
    {
        RvcStatus s = this.status.get();
        return s != null && s.gpu != null && s.gpu.available;
    }

    /** @return Unmodifiable list of available RVC model names, or empty list. */
    public List<String> getAvailableModels()
    {
        RvcStatus s = this.status.get();
        if (s != null && s.modelsAvailable != null)
            return Collections.unmodifiableList(s.modelsAvailable);
        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Replace the active RVC configuration. */
    public void setConfig(RvcConfig config)
    {
        this.config = config != null ? config : new RvcConfig();
    }

    // -------------------------------------------------------------------------
    // Audio conversion
    // -------------------------------------------------------------------------

    /**
     * Convert audio using RVC voice conversion.
     *
     * @param inputWav   Input WAV file path.
     * @param outputWav  Output WAV file path (may equal inputWav for in-place).
     * @return CompletableFuture resolving to the output path, or the original
     *         input path if conversion was skipped or failed.
     */
    public CompletableFuture<Path> convert(Path inputWav, Path outputWav)
    {
        if (!this.isAvailable())
        {
            LOG.debug("RVC not available, skipping conversion");
            return CompletableFuture.completedFuture(inputWav);
        }

        if (!this.config.enabled)
        {
            LOG.debug("RVC disabled in config, skipping conversion");
            return CompletableFuture.completedFuture(inputWav);
        }
        if (this.config.modelPath == null || this.config.modelPath.trim().isEmpty())
        {
            LOG.warn("RVC enabled but no model is configured; skipping conversion");
            return CompletableFuture.completedFuture(inputWav);
        }

        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return doConversion(inputWav, outputWav, null);
            }
            catch (Exception ex)
            {
                LOG.error("RVC conversion failed", ex);
                return inputWav; // return original on failure
            }
        }, this.executor);
    }

    /**
     * Convert audio using an <em>explicit</em> RVC model, bypassing the
     * global {@link RvcConfig#enabled} flag.
     *
     * <p>Used by the TTS virtual-voice path: when the user picks an
     * {@code "RVC: &lt;model&gt;"} entry from the voice list they are
     * explicitly asking for that model, so the global "RVC enabled"
     * toggle (which governs the per-session post-processing behaviour)
     * should not be able to veto them.  All other config knobs (pitch,
     * method, index rate, device, timeout…) are still taken from the
     * current {@link RvcConfig}.</p>
     *
     * <p>If RVC itself is unavailable (deps missing / Python
     * incompatible / bridge not found) this still short-circuits to the
     * original input path — the TTS pipeline degrades to playing the
     * untreated base-voice audio rather than failing outright.</p>
     *
     * @param inputWav      the raw TTS output WAV
     * @param outputWav     destination path for the converted WAV
     * @param modelPath     model path to use, <em>overrides</em>
     *                      {@link RvcConfig#modelPath} for this call.  May
     *                      be absolute, or relative to the models directory
     *                      (same semantics as {@code RvcConfig.modelPath}).
     * @return future resolving to {@code outputWav} on success, or
     *         {@code inputWav} if RVC is unavailable / model missing /
     *         conversion fails.
     */
    public CompletableFuture<Path> convertWithModel(Path inputWav, Path outputWav, String modelPath)
    {
        if (!this.isAvailable())
        {
            LOG.warn("RVC requested with model '{}' but service is not available; "
                   + "passing TTS audio through untouched", modelPath);
            return CompletableFuture.completedFuture(inputWav);
        }
        if (modelPath == null || modelPath.isEmpty())
        {
            LOG.warn("convertWithModel called with null/empty model; falling back to global config");
            return convert(inputWav, outputWav);
        }

        final String override = modelPath;
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return doConversion(inputWav, outputWav, override);
            }
            catch (Exception ex)
            {
                LOG.error("RVC conversion failed (model={})", override, ex);
                return inputWav;
            }
        }, this.executor);
    }

    private Path doConversion(Path inputWav, Path outputWav, String modelOverride) throws Exception
    {
        // Resolve which model to use.  An explicit override (from convertWithModel)
        // wins over the config's modelPath so the TTS virtual-voice path can pick
        // a specific model per call without mutating shared config.
        final String effectiveModel = (modelOverride != null && !modelOverride.isEmpty())
            ? modelOverride
            : this.config.modelPath;
        // The index companion file only makes sense for the config's own model,
        // since overrides come from the voice-list and we don't maintain a
        // <model>→<index> map yet.  If the override happens to match the config
        // model we still apply the index for parity with the non-override path.
        final String effectiveIndex =
            (modelOverride == null || modelOverride.isEmpty()
             || modelOverride.equals(this.config.modelPath))
                ? this.config.indexPath
                : null;

        List<String> args = new ArrayList<>(getPythonCommandArgs());
        args.add(getResourcePath("rvc_bridge.py").toString());
        args.add("--input");
        args.add(inputWav.toString());
        args.add("--output");
        args.add(outputWav.toString());
        // Tell the bridge where Scarlet keeps its .pth / .index files so the
        // relative-model and auto-index-pairing logic can find them even when
        // the bridge script is extracted to a different location than the
        // user-visible models directory.
        args.add("--models-dir");
        args.add(this.modelsDir.toAbsolutePath().toString());

        if (effectiveModel != null && !effectiveModel.isEmpty())
        {
            args.add("--model");
            args.add(effectiveModel);

            if (effectiveIndex != null && !effectiveIndex.isEmpty())
            {
                args.add("--index");
                args.add(effectiveIndex);
            }
        }

        args.add("--pitch");
        args.add(String.valueOf(this.config.pitch));
        args.add("--method");
        args.add(this.config.method);
        args.add("--index-rate");
        args.add(String.valueOf(this.config.indexRate));
        args.add("--filter-radius");
        args.add(String.valueOf(this.config.filterRadius));
        args.add("--resample-sr");
        args.add(String.valueOf(this.config.resampleSr));
        args.add("--rms-mix-rate");
        args.add(String.valueOf(this.config.rmsMixRate));
        args.add("--protect");
        args.add(String.valueOf(this.config.protect));
        args.add("--device");
        args.add(this.config.device != null ? this.config.device : "auto");
        args.add("--timeout");
        args.add(String.valueOf(this.config.timeoutSeconds));

        LOG.info("Running RVC conversion: model={}, pitch={}, method={}",
            effectiveModel, this.config.pitch, this.config.method);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(false); // keep stderr separate; only parse stdout
        Process proc = pb.start();

        // Drain stderr to logger so nothing blocks
        final Process procRef = proc;
        Thread stderrDrainer = new Thread(() ->
        {
            try (BufferedReader br =
                     new BufferedReader(new InputStreamReader(procRef.getErrorStream())))
            {
                String line;
                while ((line = br.readLine()) != null)
                    LOG.debug("[rvc_bridge] {}", line);
            }
            catch (IOException ignored) {}
        }, "RVC-StderrDrainer");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();

        // Read stdout — expected to be a single JSON object
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                 new BufferedReader(new InputStreamReader(proc.getInputStream())))
        {
            String line;
            while ((line = reader.readLine()) != null)
                output.append(line).append('\n');
        }

        boolean finished = proc.waitFor(this.config.timeoutSeconds + 30L, TimeUnit.SECONDS);
        if (!finished)
        {
            LOG.error("RVC conversion timed out after {}s — destroying bridge process",
                this.config.timeoutSeconds + 30L);
            proc.destroyForcibly();
            throw new IOException("RVC conversion timed out");
        }

        int exitCode = proc.exitValue();
        String stdout = output.toString().trim();

        // Parse the bridge's JSON result.  A well-behaved bridge always
        // prints one JSON object on stdout even on failure; exit code is
        // a secondary signal.  Defensive: third-party libraries that
        // rvc-python depends on (fairseq, torch, faiss, ...) occasionally
        // leak prints to stdout.  Rather than parsing the whole buffer
        // (which breaks on any stray token), we locate the LAST balanced
        // {...} JSON object in stdout and parse just that slice.
        JsonObject result = null;
        if (!stdout.isEmpty())
        {
            String jsonSlice = extractLastJsonObject(stdout);
            if (jsonSlice != null)
            {
                try
                {
                    result = GSON.fromJson(jsonSlice, JsonObject.class);
                }
                catch (Exception parseEx)
                {
                    LOG.error("Failed to parse RVC bridge JSON slice: {}", jsonSlice, parseEx);
                }
            }
            if (result == null)
            {
                // Fallback: try the whole buffer as-is so we still log
                // a useful parse error if the slice-finder came up empty.
                try
                {
                    result = GSON.fromJson(stdout, JsonObject.class);
                }
                catch (Exception parseEx)
                {
                    LOG.error("Failed to parse RVC bridge JSON output: {}", stdout, parseEx);
                }
            }
        }

        if (result != null
            && result.has("success")
            && result.get("success").getAsBoolean())
        {
            String message = result.has("message") ? result.get("message").getAsString() : "OK";
            LOG.info("RVC conversion succeeded: {}", message);
            return outputWav;
        }

        // Failure path.  Surface any error / message fields the bridge
        // included so the user sees something useful in the log.
        String errMsg = "RVC bridge reported failure";
        if (result != null)
        {
            if (result.has("error"))
                errMsg = result.get("error").getAsString();
            else if (result.has("message"))
                errMsg = result.get("message").getAsString();
        }
        else if (!stdout.isEmpty())
        {
            errMsg = "Unparseable bridge output: " + stdout;
        }

        throw new IOException("RVC conversion failed (exit=" + exitCode + "): " + errMsg);
    }

    /**
     * Scan {@code buf} for the <em>last</em> balanced {@code {...}} JSON
     * object and return just that slice.  Returns {@code null} if no
     * balanced object is found.  The scan walks backwards from the end
     * to find a closing {@code '}'}, then walks back again tracking
     * brace depth (ignoring braces inside quoted strings) to find its
     * matching {@code '{'}.
     *
     * <p>This exists because rvc-python and its transitive dependencies
     * (fairseq, torch, faiss, ...) occasionally print progress/info
     * messages to stdout during model load and inference.  The bridge
     * does its best to route those to stderr, but this parser is the
     * belt-and-suspenders layer: even if a stray print slips through,
     * Scarlet still picks our JSON out of the noise.
     */
    private static String extractLastJsonObject(String buf)
    {
        if (buf == null || buf.isEmpty())
            return null;
        int end = -1;
        for (int i = buf.length() - 1; i >= 0; i--)
        {
            if (buf.charAt(i) == '}')
            {
                end = i;
                break;
            }
        }
        if (end < 0)
            return null;
        int depth = 0;
        boolean inString = false;
        for (int i = end; i >= 0; i--)
        {
            char c = buf.charAt(i);
            // Detect unescaped quote boundaries.  We walk backwards so
            // an escape is "the quote has an odd number of backslashes
            // immediately preceding it".
            if (c == '"')
            {
                int bs = 0;
                int j = i - 1;
                while (j >= 0 && buf.charAt(j) == '\\')
                {
                    bs++;
                    j--;
                }
                if ((bs & 1) == 0)
                    inString = !inString;
                continue;
            }
            if (inString)
                continue;
            if (c == '}')
            {
                depth++;
            }
            else if (c == '{')
            {
                depth--;
                if (depth == 0)
                {
                    return buf.substring(i, end + 1);
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Accessors used by the UI layer and TTS glue
    // -------------------------------------------------------------------------

    /**
     * @return the directory that holds {@code .pth} model files (and their
     *         companion {@code .index} retrieval files, if present).  The
     *         UI uses this for the "Open models folder" action and for
     *         copying new uploads into place.
     */
    public Path getModelsDir()
    {
        return this.modelsDir;
    }

    /** @return the current RVC configuration. */
    public RvcConfig getConfig()
    {
        return this.config;
    }

    @Override
    public void close()
    {
        this.executor.shutdown();
        try
        {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS))
                this.executor.shutdownNow();
        }
        catch (InterruptedException ex)
        {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
