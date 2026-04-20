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
 * every flag falls back to its {@link #loadFlag compile-time default}.
 *
 * <p>Default values encode <em>"what should happen if no properties file
 * is present on the classpath"</em>:
 * <ul>
 *   <li>Features that have always shipped (kick/ban, watched-avatars,
 *       calendar, aux-webhooks, animated-emoji, DAVE, evidence,
 *       avatar-search, pronouns, vrchat-reports, CLI commands) default
 *       to {@code true} so older builds and the lite edition preserve
 *       existing behaviour without needing to bundle a properties file.</li>
 *   <li>Opt-in features newer than their umbrella release (currently
 *       just {@link #RVC_ENABLED}) default to {@code false} so they're
 *       off unless a distribution explicitly opts in.</li>
 * </ul>
 *
 * <p>This lets us produce multiple distribution JARs from a single source
 * tree by picking which {@code scarlet-features.properties} gets shaded in:
 * <ul>
 *   <li><b>Full</b>: ships {@code scarlet-features.properties} with
 *       {@code rvc.enabled=true}; every other flag inherits its default
 *       ({@code true}), so all features are on.</li>
 *   <li><b>Lite</b>: strips {@code scarlet-features.properties}
 *       entirely, so every flag falls back to its compile-time default.
 *       RVC is off (opt-in default), everything else is on — existing
 *       lite behaviour is preserved byte-for-byte.</li>
 *   <li><b>Minimal</b>: ships a minimal-specific
 *       {@code scarlet-features.properties} that sets every cut flag
 *       ({@code discord.kick_ban}, {@code watched.avatars},
 *       {@code calendar}, {@code aux_webhooks}, {@code animated_emoji},
 *       {@code dave}, {@code evidence}, {@code avatar_search},
 *       {@code pronouns}, {@code vrchat_reports}, {@code cli_commands})
 *       to {@code false} alongside {@code rvc.enabled=false}, leaving
 *       TTS and the core VRChat-audit-log-to-Discord pipeline as the
 *       only active subsystems.</li>
 * </ul>
 *
 * <p>Flags evaluated here are {@code public static final}, so the JIT
 * can treat them as constants and dead-code-eliminate disabled paths
 * across the whole call graph.  That means a stripped-down build pays
 * no runtime cost for the gating: the disabled branches fold away.
 */
public final class Features
{
    private static final Logger LOG = LoggerFactory.getLogger("Scarlet/Features");

    /** Relative to classpath root.  Matches what the Maven shade filters
     *  target (see pom.xml {@code shade-lite} / {@code shade-minimal}
     *  executions). */
    private static final String RESOURCE_PATH = "/scarlet-features.properties";

    /** Loaded once at class init; empty if the resource was absent. */
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
     * Default {@code false}: RVC is newer than the lite edition and is
     * opt-in; a build without the properties file gets no RVC.
     */
    public static final boolean RVC_ENABLED = loadFlag("rvc.enabled", false);

    /**
     * Whether the built-in Discord {@code /discord-kick} and
     * {@code /discord-ban} slash commands are registered and their
     * settings toggle ({@code discordKickBanEnabled}) is exposed in the
     * UI.  Orthogonal to the runtime toggle — when this compile-time
     * flag is false, the commands are never registered regardless of
     * the runtime toggle's state.  Default {@code true} (feature has
     * shipped since 0.4.16-b4).
     */
    public static final boolean DISCORD_KICK_BAN_ENABLED = loadFlag("discord.kick_ban.enabled", true);

    /**
     * Whether the {@code /watched-avatar} command family and the
     * watched-avatar detection branch of the event listener are active.
     * Does <b>not</b> affect watched-groups or watched-users, which
     * share the same {@code WatchedEntity_<T>} base class but are
     * independently controlled.  Default {@code true}.
     */
    public static final boolean WATCHED_AVATARS_ENABLED = loadFlag("watched.avatars.enabled", true);

    /**
     * Whether the {@link net.sybyline.scarlet.ScarletCalendar} subsystem
     * (event scheduling, periodic reports, {@code /schedule} command)
     * is active.  Default {@code true}.
     */
    public static final boolean CALENDAR_ENABLED = loadFlag("calendar.enabled", true);

    /**
     * Whether the {@code /aux-webhooks} and
     * {@code /set-audit-aux-webhooks} commands + the aux-webhook
     * routing they drive are active.  Default {@code true}.
     */
    public static final boolean AUX_WEBHOOKS_ENABLED = loadFlag("aux_webhooks.enabled", true);

    /**
     * Whether the {@code /vrchat-animated-emoji} slash/message command
     * and its GIF → Discord-sprite-sheet backend are registered.
     * Default {@code true}.
     */
    public static final boolean ANIMATED_EMOJI_ENABLED = loadFlag("animated_emoji.enabled", true);

    /**
     * Whether DAVE (Discord Audio &amp; Video Encryption) is wired into
     * the JDA audio stack.  When false the JDA voice-init site skips
     * installing {@code LDaveSessionFactory}; Scarlet still participates
     * in voice channels via non-E2EE transport.  Lets minimal builds
     * skip shipping the ~1.5 MB per-platform native libraries bundled
     * for Linux, Windows, and macOS. Default {@code true}.
     */
    public static final boolean DAVE_ENABLED = loadFlag("dave.enabled", true);

    /**
     * Whether moderation-case evidence and attachment tracking
     * ({@link net.sybyline.scarlet.ScarletEvidence} and its settings in
     * {@code ScarletDiscordJDA}) are active.  Default {@code true}.
     */
    public static final boolean EVIDENCE_ENABLED = loadFlag("evidence.enabled", true);

    /**
     * Whether the avatar-search subsystem is active.  Gates:
     * <ul>
     *   <li>The {@code /vrchat-search avatar} slash command.</li>
     *   <li>The "view potential avatar matches" button handler.</li>
     *   <li>Runtime avatar lookups in the event listener
     *       ({@code searchAvatar} and the image-match branch of
     *       {@code switchPlayerAvatar}).</li>
     *   <li>Avatar-search settings in the UI settings pane.</li>
     * </ul>
     * Does <b>not</b> affect watched-avatars (see
     * {@link #WATCHED_AVATARS_ENABLED}), which uses a separate lookup
     * path.  Default {@code true}.
     */
    public static final boolean AVATAR_SEARCH_ENABLED = loadFlag("avatar_search.enabled", true);

    /**
     * Whether the pronoun-validation subsystem is active.  Gates:
     * <ul>
     *   <li>The {@code ScarletPronounLists} initializer in
     *       {@link net.sybyline.scarlet.Scarlet}.</li>
     *   <li>The pronoun-flag check inside the event listener's
     *       display-name/bio scan.</li>
     *   <li>Good/bad-pronoun-list editor buttons and
     *       reload-pronoun-lists action in the UI.</li>
     * </ul>
     * Default {@code true}.
     */
    public static final boolean PRONOUNS_ENABLED = loadFlag("pronouns.enabled", true);

    /**
     * Whether the VRChat-reports subsystem is active.  Gates:
     * <ul>
     *   <li>The {@code /vrchat-report-template} slash command family.</li>
     *   <li>The "vrchat-report" Discord button handler.</li>
     * </ul>
     * Does <b>not</b> affect Discord-native moderation cases, which are
     * controlled independently.  Default {@code true}.
     */
    public static final boolean VRCHAT_REPORTS_ENABLED = loadFlag("vrchat_reports.enabled", true);

    /**
     * Whether Scarlet's interactive CLI (the stdin-driven
     * {@code spin()} loop and the {@code runCliCommand} setting) is
     * active.  When false, stdin is ignored entirely — the process is
     * driven exclusively by the GUI / Discord / schedule subsystems.
     * Default {@code true}.
     */
    public static final boolean CLI_COMMANDS_ENABLED = loadFlag("cli_commands.enabled", true);

    /**
     * Resolve a feature flag by its properties-file key.
     * Used by declarative registration gates such as Discord commands.
     */
    public static boolean isEnabled(String key)
    {
        switch (key)
        {
        case "rvc.enabled":               return RVC_ENABLED;
        case "discord.kick_ban.enabled":  return DISCORD_KICK_BAN_ENABLED;
        case "watched.avatars.enabled":   return WATCHED_AVATARS_ENABLED;
        case "calendar.enabled":          return CALENDAR_ENABLED;
        case "aux_webhooks.enabled":      return AUX_WEBHOOKS_ENABLED;
        case "animated_emoji.enabled":    return ANIMATED_EMOJI_ENABLED;
        case "dave.enabled":              return DAVE_ENABLED;
        case "evidence.enabled":          return EVIDENCE_ENABLED;
        case "avatar_search.enabled":     return AVATAR_SEARCH_ENABLED;
        case "pronouns.enabled":          return PRONOUNS_ENABLED;
        case "vrchat_reports.enabled":    return VRCHAT_REPORTS_ENABLED;
        case "cli_commands.enabled":      return CLI_COMMANDS_ENABLED;
        default:
            LOG.warn("Unknown feature flag key `{}` requested; defaulting to false", key);
            return false;
        }
    }

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
                LOG.info("No {} on classpath; using compile-time defaults for all feature flags", RESOURCE_PATH);
                return p;
            }
            p.load(in);
            LOG.info("Loaded {} feature flag entries from {}", p.size(), RESOURCE_PATH);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to read {}; falling back to compile-time defaults", RESOURCE_PATH, ex);
        }
        return p;
    }

    private static boolean loadFlag(String key, boolean defaultValue)
    {
        String raw = PROPS.getProperty(key);
        if (raw == null)
            return defaultValue;
        raw = raw.trim();
        if (raw.isEmpty())
            return defaultValue;
        return Boolean.parseBoolean(raw);
    }

    private Features() {}
}
