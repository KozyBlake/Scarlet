package net.sybyline.scarlet.util.rvc;

import java.util.List;
import java.util.Map;

import com.google.gson.annotations.SerializedName;

/**
 * Status information about the RVC environment.
 *
 * <p>Contains hardware detection results, dependency status, and available
 * models.  This mirrors the JSON shape produced by
 * {@code rvc_bridge.py --status}.</p>
 *
 * <h2>Schema versioning</h2>
 * <p>The script populates a {@link #schemaVersion} field — currently 2.
 * Consumers should check this before reading v2 fields like
 * {@link #system}, {@link #python}, {@link #ffmpeg}, {@link #gpus},
 * {@link #packageVersions}, {@link #recommendedInstall} and
 * {@link #externalInstalls}.  When {@code schemaVersion} is 0 or 1 those
 * fields may be {@code null} — fall back to {@link #gpu} /
 * {@link #dependenciesMissing}.</p>
 *
 * <h2>Legacy fields</h2>
 * <p>{@link #gpu}, {@link #dependenciesMissing}, {@link #rvcCompatible},
 * {@link #modelsAvailable} and {@link #modelsDir} are preserved unchanged
 * so older code keeps working.</p>
 */
public class RvcStatus
{
    // =======================================================================
    // Schema versioning
    // =======================================================================

    /** Bridge script schema version.  {@code 0} when unknown/legacy. */
    @SerializedName("schema_version")
    public int schemaVersion = 0;

    // =======================================================================
    // v2 fields — may be null when talking to an older bridge script
    // =======================================================================

    /** OS / arch / host info.  Independent of torch. */
    @SerializedName("system")
    public SystemInfo system = null;

    /** Python interpreter details. */
    @SerializedName("python")
    public PythonInfo python = null;

    /** FFmpeg availability (required for non-WAV inputs). */
    @SerializedName("ffmpeg")
    public FfmpegInfo ffmpeg = null;

    /**
     * All GPUs detected across every backend (CUDA, ROCm, XPU, DirectML).
     * Use this instead of {@link #gpu} when the install UI needs to show
     * the user what was found.  {@link #gpu} is still populated as the
     * "primary" pick for quick checks.
     */
    @SerializedName("gpus")
    public List<GpuInfo> gpus = null;

    /** Installed version per pip package (null when missing). */
    @SerializedName("package_versions")
    public Map<String, String> packageVersions = null;

    /**
     * What the bridge recommends we install: which torch wheel + index-url
     * to use, along with a human-readable reason.  Drives the consent
     * dialog and the {@code --install} flow.
     */
    @SerializedName("recommended_install")
    public RecommendedInstall recommendedInstall = null;

    /** Detected stand-alone RVC-WebUI / Mangio-RVC installs on disk. */
    @SerializedName("external_installs")
    public List<ExternalInstall> externalInstalls = null;

    // =======================================================================
    // Legacy fields — preserved for backwards compatibility
    // =======================================================================

    /** Whether RVC is fully compatible and ready to use. */
    @SerializedName("rvc_compatible")
    public boolean rvcCompatible = false;

    /** List of missing Python dependencies.  Empty when all deps are present. */
    @SerializedName("dependencies_missing")
    public List<String> dependenciesMissing = null;

    /** Primary GPU (first NVIDIA > ROCm > XPU > DirectML).  Never {@code null}. */
    @SerializedName("gpu")
    public GpuInfo gpu = new GpuInfo();

    /** List of available RVC models. */
    @SerializedName("models_available")
    public List<String> modelsAvailable = null;

    /** Path to the models directory. */
    @SerializedName("models_dir")
    public String modelsDir = null;

    // =======================================================================
    // Convenience accessors
    // =======================================================================

    /** @return {@code true} if RVC is ready to use. */
    public boolean isRvcCompatible()
    {
        return rvcCompatible;
    }

    /** @return {@code true} if any accelerator (CUDA/ROCm/XPU/DirectML) is usable. */
    public boolean isGpuAvailable()
    {
        return gpu != null && gpu.available;
    }

    /**
     * @return {@code true} if the bridge reports Python within the supported
     *         range (currently 3.9\u20133.11).  Returns {@code false} for both
     *         too-old and too-new interpreters.
     */
    public boolean isPythonCompatible()
    {
        return python != null && python.isCompatible;
    }

    /** @return {@code true} if the bridge flagged Python as newer than supported. */
    public boolean isPythonTooNew()
    {
        return python != null && python.tooNew;
    }

    /** @return {@code true} if the bridge flagged Python as older than supported. */
    public boolean isPythonTooOld()
    {
        return python != null && python.tooOld;
    }

    /** @return {@code true} if FFmpeg is on PATH (needed for non-WAV input). */
    public boolean isFfmpegAvailable()
    {
        return ffmpeg != null && ffmpeg.available;
    }

    /** @return number of GPUs the bridge reported, counting all backends. */
    public int gpuCount()
    {
        return gpus == null ? 0 : gpus.size();
    }

    // =======================================================================
    // Nested types
    // =======================================================================

    /** OS / architecture / host. */
    public static class SystemInfo
    {
        @SerializedName("os")          public String  os          = null;   // "Windows" / "Linux" / "Darwin"
        @SerializedName("os_release")  public String  osRelease   = null;
        @SerializedName("os_version")  public String  osVersion   = null;
        @SerializedName("arch")        public String  arch        = null;   // "x86_64" / "arm64" / ...
        @SerializedName("machine_raw") public String  machineRaw  = null;
        @SerializedName("is_wsl")      public boolean isWsl       = false;
        @SerializedName("hostname")    public String  hostname    = null;

        @Override
        public String toString()
        {
            if (os == null)
                return "unknown system";
            String suffix = isWsl ? " (WSL)" : "";
            return String.format("%s %s (%s)%s",
                os,
                osRelease != null ? osRelease : "",
                arch != null ? arch : "?",
                suffix).trim();
        }
    }

    /** Python interpreter details. */
    public static class PythonInfo
    {
        @SerializedName("version")        public String  version        = null;
        @SerializedName("version_tuple")  public List<Integer> versionTuple = null;
        @SerializedName("executable")     public String  executable     = null;
        @SerializedName("implementation") public String  implementation = null;
        @SerializedName("is_compatible")  public boolean isCompatible   = false;
        @SerializedName("too_old")        public boolean tooOld         = false;
        @SerializedName("too_new")        public boolean tooNew         = false;
        @SerializedName("min_required")   public String  minRequired    = null;
        @SerializedName("max_supported")  public String  maxSupported   = null;
        @SerializedName("incompatible_reason") public String incompatibleReason = null;

        @Override
        public String toString()
        {
            if (version == null)
                return "unknown python";
            String tag;
            if (isCompatible)
                tag = "";
            else if (tooNew && maxSupported != null)
                tag = String.format(" (too new — max %s)", maxSupported);
            else if (tooOld && minRequired != null)
                tag = String.format(" (too old — min %s)", minRequired);
            else if (minRequired != null && maxSupported != null)
                tag = String.format(" (need %s\u2013%s)", minRequired, maxSupported);
            else
                tag = " (incompatible)";
            return String.format("%s %s @ %s%s",
                implementation != null ? implementation : "Python",
                version,
                executable != null ? executable : "?",
                tag);
        }
    }

    /** FFmpeg availability. */
    public static class FfmpegInfo
    {
        @SerializedName("available") public boolean available = false;
        @SerializedName("path")      public String  path      = null;
        @SerializedName("version")   public String  version   = null;

        @Override
        public String toString()
        {
            if (!available)
                return "FFmpeg not found";
            return String.format("FFmpeg %s (%s)",
                version != null ? version : "?",
                path    != null ? path    : "?");
        }
    }

    /** Bridge's recommended torch wheel for this machine. */
    public static class RecommendedInstall
    {
        @SerializedName("label")       public String label      = null;
        @SerializedName("tag")         public String tag        = null;   // cpu | cu118 | cu121 | cu124 | rocm6.0 | xpu | directml
        @SerializedName("index_url")   public String indexUrl   = null;
        @SerializedName("reason")      public String reason     = null;
        @SerializedName("device_hint") public String deviceHint = null;

        @Override
        public String toString()
        {
            return label != null ? label : "unknown install target";
        }
    }

    /** External RVC-WebUI install found on disk. */
    public static class ExternalInstall
    {
        @SerializedName("path")       public String  path       = null;
        @SerializedName("kind")       public String  kind       = null;   // rvc-webui | mangio-rvc-fork | rvc
        @SerializedName("has_config") public boolean hasConfig = false;

        @Override
        public String toString()
        {
            return String.format("%s at %s", kind != null ? kind : "rvc", path);
        }
    }

    /**
     * GPU hardware information.
     *
     * <p>Kept backwards-compatible: all legacy fields ({@code available},
     * {@code device}, {@code name}, {@code memoryGb}, {@code cudaVersion})
     * still exist.  New fields like {@link #backend},
     * {@link #computeCapability}, {@link #driverVersion},
     * {@link #detectedVia} and {@link #hipVersion} are populated by the v2
     * bridge and may be {@code null} with an older script.</p>
     */
    public static class GpuInfo
    {
        @SerializedName("available")
        public boolean available = false;

        @SerializedName("device")
        public String device = "cpu";

        @SerializedName("name")
        public String name = "Unknown";

        @SerializedName("memory_gb")
        public float memoryGb = 0.0f;

        @SerializedName("cuda_version")
        public String cudaVersion = null;

        // ---- v2 additions ---------------------------------------------------

        /** 0-based device index within its backend. */
        @SerializedName("index")
        public int index = 0;

        /** "cuda" | "rocm" | "xpu" | "directml" | "unknown".  Null on legacy. */
        @SerializedName("backend")
        public String backend = null;

        /** e.g. "8.9" for Ada Lovelace.  Null if unknown. */
        @SerializedName("compute_capability")
        public String computeCapability = null;

        /** GPU driver version (NVIDIA driver etc.).  Null if unknown. */
        @SerializedName("driver_version")
        public String driverVersion = null;

        /** How we detected the device: "torch" / "nvidia-smi" / "rocm-smi" / "wmic" / ... */
        @SerializedName("detected_via")
        public String detectedVia = null;

        /** ROCm build's HIP version, if applicable. */
        @SerializedName("hip_version")
        public String hipVersion = null;

        @Override
        public String toString()
        {
            if (!available)
                return "CPU (No GPU detected)";
            StringBuilder sb = new StringBuilder();
            sb.append(name != null ? name : "GPU");
            sb.append(String.format(" (%.1f GB", memoryGb));
            if (backend != null)
                sb.append(", ").append(backend);
            if (cudaVersion != null)
                sb.append(", CUDA ").append(cudaVersion);
            else if (hipVersion != null)
                sb.append(", HIP ").append(hipVersion);
            sb.append(")");
            return sb.toString();
        }
    }

    // =======================================================================
    // Debug formatting
    // =======================================================================

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("RVC Status (schema v").append(schemaVersion).append("):\n");
        sb.append("  Compatible: ").append(rvcCompatible).append("\n");
        if (system != null)
            sb.append("  System:     ").append(system).append("\n");
        if (python != null)
            sb.append("  Python:     ").append(python).append("\n");
        if (ffmpeg != null)
            sb.append("  FFmpeg:     ").append(ffmpeg).append("\n");
        sb.append("  Primary GPU: ").append(gpu).append("\n");
        if (gpus != null && gpus.size() > 1)
        {
            sb.append("  Other GPUs:\n");
            for (int i = 1; i < gpus.size(); i++)
                sb.append("    [").append(i).append("] ").append(gpus.get(i)).append("\n");
        }
        if (recommendedInstall != null)
            sb.append("  Recommend:  ").append(recommendedInstall).append("\n");
        if (dependenciesMissing != null && !dependenciesMissing.isEmpty())
            sb.append("  Missing:    ").append(dependenciesMissing).append("\n");
        if (packageVersions != null && !packageVersions.isEmpty())
            sb.append("  Versions:   ").append(packageVersions).append("\n");
        if (externalInstalls != null && !externalInstalls.isEmpty())
            sb.append("  External:   ").append(externalInstalls).append("\n");
        if (modelsAvailable != null && !modelsAvailable.isEmpty())
            sb.append("  Models:     ").append(modelsAvailable).append("\n");
        return sb.toString();
    }
}
