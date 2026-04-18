package net.sybyline.scarlet.util.rvc;

import com.google.gson.annotations.SerializedName;

/**
 * Configuration for RVC (Retrieval-based Voice Conversion) voice conversion.
 * 
 * <p>This class holds all configurable parameters for RVC inference,
 * including model selection, pitch adjustment, and performance settings.</p>
 */
public class RvcConfig
{
    /**
     * Whether RVC voice conversion is enabled.
     */
    @SerializedName("enabled")
    public boolean enabled = false;

    /**
     * Path to the RVC model file (.pth).
     * Can be an absolute path or relative to the models directory.
     */
    @SerializedName("model_path")
    public String modelPath = "";

    /**
     * Path to the RVC index file (.index) for improved quality.
     * Optional but recommended for best results.
     */
    @SerializedName("index_path")
    public String indexPath = "";

    /**
     * Pitch adjustment in semitones.
     * Range: -24 to +24, default 0 (no adjustment).
     * Use positive values to raise pitch, negative to lower.
     */
    @SerializedName("pitch")
    public int pitch = 0;

    /**
     * Pitch extraction method.
     * Options: "pm" (fast), "harvest", "crepe" (quality), "rmvpe" (recommended).
     */
    @SerializedName("method")
    public String method = "rmvpe";

    /**
     * Feature search ratio (index rate).
     * Range: 0.0 to 1.0, default 0.5.
     * Higher values use more from the index file for better timbre matching.
     */
    @SerializedName("index_rate")
    public float indexRate = 0.5f;

    /**
     * Median filtering radius for pitch.
     * Range: 0 to 7, default 3.
     * Higher values smooth out pitch variations more.
     */
    @SerializedName("filter_radius")
    public int filterRadius = 3;

    /**
     * Output resampling rate in Hz.
     * 0 means no resampling (keep original rate).
     * Common values: 0, 22050, 44100, 48000.
     */
    @SerializedName("resample_sr")
    public int resampleSr = 0;

    /**
     * Volume envelope mix rate.
     * Range: 0.0 to 1.0, default 0.25.
     * Controls how much of the original volume envelope is preserved.
     */
    @SerializedName("rms_mix_rate")
    public float rmsMixRate = 0.25f;

    /**
     * Protection for voiceless consonants.
     * Range: 0.0 to 0.5, default 0.33.
     * Higher values protect more but may reduce voice clarity.
     */
    @SerializedName("protect")
    public float protect = 0.33f;

    /**
     * Device to use for inference.
     * Options: "auto", "cpu", "cuda:0", "cuda:1", etc.
     * "auto" will use GPU if available, otherwise CPU.
     */
    @SerializedName("device")
    public String device = "auto";

    /**
     * Timeout for conversion in seconds.
     * Default 120 seconds (2 minutes) should handle most cases.
     * Shorter audio clips will complete much faster.
     */
    @SerializedName("timeout_seconds")
    public int timeoutSeconds = 120;

    /**
     * Whether to fallback to passthrough on error.
     * If true, failed conversions will return the original audio.
     * If false, failures will throw exceptions.
     */
    @SerializedName("fallback_on_error")
    public boolean fallbackOnError = true;

    /**
     * Minimum GPU VRAM in GB required to use GPU acceleration.
     * Systems with less VRAM will fall back to CPU.
     */
    @SerializedName("min_gpu_vram_gb")
    public float minGpuVramGb = 4.0f;

    /**
     * Creates a default configuration with RVC disabled.
     */
    public RvcConfig()
    {
    }

    /**
     * Creates a copy of another configuration.
     */
    public RvcConfig copy()
    {
        RvcConfig copy = new RvcConfig();
        copy.enabled = this.enabled;
        copy.modelPath = this.modelPath;
        copy.indexPath = this.indexPath;
        copy.pitch = this.pitch;
        copy.method = this.method;
        copy.indexRate = this.indexRate;
        copy.filterRadius = this.filterRadius;
        copy.resampleSr = this.resampleSr;
        copy.rmsMixRate = this.rmsMixRate;
        copy.protect = this.protect;
        copy.device = this.device;
        copy.timeoutSeconds = this.timeoutSeconds;
        copy.fallbackOnError = this.fallbackOnError;
        copy.minGpuVramGb = this.minGpuVramGb;
        return copy;
    }

    /**
     * Validates the configuration and returns an error message, or null if valid.
     */
    public String validate()
    {
        if (pitch < -24 || pitch > 24)
            return "Pitch must be between -24 and +24 semitones";
        
        if (indexRate < 0.0f || indexRate > 1.0f)
            return "Index rate must be between 0.0 and 1.0";
        
        if (filterRadius < 0 || filterRadius > 7)
            return "Filter radius must be between 0 and 7";
        
        if (rmsMixRate < 0.0f || rmsMixRate > 1.0f)
            return "RMS mix rate must be between 0.0 and 1.0";
        
        if (protect < 0.0f || protect > 0.5f)
            return "Protect must be between 0.0 and 0.5";
        
        if (timeoutSeconds < 10)
            return "Timeout must be at least 10 seconds";
        
        if (!method.equals("pm") && !method.equals("harvest") 
            && !method.equals("crepe") && !method.equals("rmvpe"))
            return "Method must be one of: pm, harvest, crepe, rmvpe";
        
        return null; // Valid
    }
}