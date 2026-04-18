package net.sybyline.scarlet;

import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compile-once, ship-many feature flags.
 *
 * <p>At startup we look for a classpath resource named
 * {@code /scarlet-features.properties}.  When present its boolean entries
 * decide which optional subsystems are active; when absent (or empty)
 * every flag falls back to its {@link #loadFlag compile-time default},
 * which is deliberately conservative ({@code false}).
 *
 * <p>This lets us produce multiple distribution JARs from a single source
 * tree by picking which {@code scarlet-features.properties} gets shaded
 * in.  The Maven {@code lite} profile strips the resource so end-users
 * get a build with no RVC UI, no Python-bridge install flow, and none
 * of the torch/torchaudio dependency checking — while the default
 * ("full") profile bundles the file with {@code rvc.enabled=true} so
 * existing behaviour is preserved.
 *
 * <p>Flags evaluated here are {@code public static final}, so the JIT
 * can treat them as constants and dead-code-eliminate disabled paths
 * across the whole call graph.  That means a lite build pays no runtime
 * cost for RVC gating: the disabled branches fold away.
 */
public final class Features
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/Features");

    /** Relative to classpath root.  Matches what the Maven shade filter
     *  targets (see pom.xml {@code lite} profile). */
    private static final String RESOURCE_PATH = "/scarlet-features.properties";

    /** Loaded once at class init; null if the resource was absent. */
    private static final Properties PROPS = loadProperties();

    /**
     * Whether the RVC (Retrieval-based Voice Conversion) subsystem is
     * compiled-in AND bundled with its Python bridge.  When false:
     * <ul>
     *   <li>RVC-related settings are hidden from the UI.</li>
     *   <li>{@link net.sybyline.scarlet.util.tts.TtsService} skips
     *       RvcService creation (and the dependency-install flow).</li>
     *   <li>The Python bridge resource {@code /rvc/rvc_bridge.py} is not
     *       shipped, reducing JAR size and removing all RVC-adjacent
     *       network/disk operations.</li>
     * </ul>
     */
    public static final boolean RVC_ENABLED = loadFlag("rvc.enabled", false);

    /** @return the raw {@link Properties} loaded from the features file
     *          (never null — may be empty).  Useful for diagnostic
     *          logging. */
    public static Properties properties()
    {
        return PROPS;
    }

    private static Properties loadProperties()
    {
        Properties p = new Properties();
        try (InputStream in = Features.class.getResourceAsStream(RESOURCE_PATH))
        {
            if (in == null)
            {
                LOG.debug("No {} on classpath — all feature flags default to off",
                    RESOURCE_PATH);
                return p;
            }
            p.load(in);
            LOG.info("Loaded {} feature flag(s) from {}", p.size(), RESOURCE_PATH);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to read {} — feature flags default to off: {}",
                RESOURCE_PATH, ex.toString());
        }
        return p;
    }

    private static boolean loadFlag(String key, boolean def)
    {
        String raw = PROPS.getProperty(key);
        if (raw == null)
            return def;
        raw = raw.trim();
        if (raw.isEmpty())
            return def;
        return Boolean.parseBoolean(raw);
    }

    private Features()
    {
        throw new AssertionError("no instances");
    }
}
