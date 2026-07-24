package net.sybyline.scarlet.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tiny translation lookup for Scarlet's user-facing text.
 *
 * <p>Strings live in {@code /net/sybyline/scarlet/i18n/messages.properties} (English,
 * the base/fallback) plus {@code messages_<lang>.properties} per language. Lookups
 * fall back language → English → the key itself, so a missing translation degrades to
 * English rather than blanking out.
 *
 * <p>The desktop UI locale defaults to the operating system's language
 * ({@link Locale#getDefault()}); a saved override can pin a specific language. Discord
 * uses {@link #tr(Locale, String, Object...)} with each interacting user's own Discord
 * locale, so members see replies in their language regardless of the desktop setting.
 *
 * <p>Properties files are read as UTF-8 (Java 8's {@code ResourceBundle} would read
 * them as Latin-1 and corrupt umlauts/accents), and parsed once then cached.
 */
public final class I18n
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/I18n");

    private static final String BASE = "/net/sybyline/scarlet/i18n/messages";

    /** Languages Scarlet ships translations for (base English is always present). */
    public static final String[] SHIPPED_LANGUAGES = { "en", "de", "es", "id", "ru" };

    private static final Map<String, Map<String, String>> byLang = new ConcurrentHashMap<>();
    private static volatile Locale locale = Locale.getDefault();
    /** Optional external folder of {@code messages_<lang>.properties} files, overlaid over bundled ones. */
    private static volatile File externalDir = null;

    private static Map<String, String> bundle(String lang)
    {
        return byLang.computeIfAbsent(normalizeLang(lang), I18n::loadLang);
    }

    /**
     * Java maps a few modern ISO-639 codes to their deprecated forms in
     * {@link Locale#getLanguage()} — notably Indonesian {@code id -> in}, Hebrew
     * {@code he -> iw}, Yiddish {@code yi -> ji}. Our bundle files use the modern
     * codes, so map back or Indonesian users would silently get English.
     */
    static String normalizeLang(String lang)
    {
        if (lang == null)
            return "";
        switch (lang)
        {
        case "in": return "id";
        case "iw": return "he";
        case "ji": return "yi";
        default:   return lang;
        }
    }

    private static String fileName(String lang)
    {
        return lang.isEmpty() ? "messages.properties" : "messages_" + lang + ".properties";
    }

    private static void loadInto(Map<String, String> out, InputStream in) throws Exception
    {
        Properties props = new Properties();
        props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String key : props.stringPropertyNames())
            out.put(key, props.getProperty(key));
    }

    private static Map<String, String> loadLang(String lang)
    {
        Map<String, String> out = new LinkedHashMap<>();
        // 1. Bundled translation (shipped inside the jar).
        String path = BASE + (lang.isEmpty() ? "" : "_" + lang) + ".properties";
        try (InputStream in = I18n.class.getResourceAsStream(path))
        {
            if (in != null)
                loadInto(out, in);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed loading bundled i18n {}", path, ex);
        }
        // 2. External override/addition from the lang folder — lets translators drop
        //    in or fix a translation (even a whole new language) without a rebuild.
        File dir = externalDir;
        if (dir != null)
        {
            File file = new File(dir, fileName(lang));
            if (file.isFile())
            {
                try (InputStream in = Files.newInputStream(file.toPath()))
                {
                    loadInto(out, in); // external keys win over bundled
                }
                catch (Exception ex)
                {
                    LOG.warn("Failed loading external i18n {}", file, ex);
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Translate {@code key} in the current desktop locale, formatting with {@code args}. */
    public static String tr(String key, Object... args)
    {
        return tr(locale, key, args);
    }

    /** Translate {@code key} in a specific locale (e.g. a Discord user's), formatting with {@code args}. */
    public static String tr(Locale loc, String key, Object... args)
    {
        if (key == null)
            return "";
        String lang = normalizeLang(loc == null ? "" : loc.getLanguage());
        String value = bundle(lang).get(key);
        if (value == null && !lang.isEmpty())
            value = bundle("").get(key); // fall back to English base
        if (value == null)
            return args != null && args.length > 0 ? key : key; // last resort: the key
        if (args == null || args.length == 0)
            return value;
        try
        {
            return MessageFormat.format(value, args);
        }
        catch (RuntimeException ex)
        {
            return value;
        }
    }

    /** True when a translation for {@code key} exists in the given language (not counting fallback). */
    public static boolean has(Locale loc, String key)
    {
        return bundle(normalizeLang(loc == null ? "" : loc.getLanguage())).containsKey(key);
    }

    /**
     * Maps a Discord locale tag (e.g. {@code "de"}, {@code "es-ES"}, {@code "id"}) to a
     * Java {@link Locale}, defaulting to English for null/blank/unknown. Lets the bot
     * render each interaction in the invoking user's own Discord language.
     */
    public static Locale ofDiscordTag(String discordLocaleTag)
    {
        if (discordLocaleTag == null || discordLocaleTag.trim().isEmpty())
            return Locale.ENGLISH;
        return Locale.forLanguageTag(discordLocaleTag.trim());
    }

    /**
     * Points Scarlet at a folder of external {@code messages_<lang>.properties} files
     * that overlay (and can extend beyond) the bundled translations, and writes an
     * up-to-date English template there for translators. Community translations can be
     * added or corrected by dropping a file in this folder — no rebuild required.
     */
    public static void setExternalDir(File dir)
    {
        externalDir = dir;
        byLang.clear(); // re-read with the external overlay in effect
        if (dir == null)
            return;
        try
        {
            if (!dir.isDirectory())
                Files.createDirectories(dir.toPath());
            exportTemplate(new File(dir, "messages.template.properties"));
        }
        catch (Exception ex)
        {
            LOG.warn("Could not set up external translations folder {}", dir, ex);
        }
    }

    /** Writes the current bundled English base to {@code out} as a starting point for translators. */
    public static void exportTemplate(File out)
    {
        try (InputStream in = I18n.class.getResourceAsStream(BASE + ".properties"))
        {
            if (in != null && out != null)
                Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception ex)
        {
            LOG.warn("Could not export translation template to {}", out, ex);
        }
    }

    /**
     * Languages that have a translation available — the shipped set plus any languages
     * present as external files. English (base) is always available.
     */
    public static Set<String> availableLanguages()
    {
        Set<String> langs = new TreeSet<>(Arrays.asList(SHIPPED_LANGUAGES));
        File dir = externalDir;
        if (dir != null && dir.isDirectory())
        {
            File[] files = dir.listFiles((d, name) -> name.startsWith("messages_") && name.endsWith(".properties"));
            if (files != null)
                for (File file : files)
                {
                    String name = file.getName();
                    String lang = name.substring("messages_".length(), name.length() - ".properties".length());
                    if (!lang.isEmpty() && !"template".equals(lang))
                        langs.add(lang.toLowerCase(Locale.ROOT));
                }
        }
        return langs;
    }

    /**
     * The Unicode script(s) native to a language. Text-to-speech uses this to keep a
     * player's name in the user's own writing system (which the matching voice can
     * pronounce) instead of romanising it. New languages are handled by adding a case
     * here; anything unlisted defaults to Latin, preserving the romanise-everything
     * behaviour English/German/Spanish/etc. rely on.
     */
    public static Set<Character.UnicodeScript> nativeScriptsForLocale(Locale loc)
    {
        String lang = normalizeLang(loc == null ? "" : loc.getLanguage());
        switch (lang)
        {
        case "ru": case "uk": case "be": case "bg": case "sr": case "mk":
        case "kk": case "ky": case "tg": case "mn":
            return Collections.singleton(Character.UnicodeScript.CYRILLIC);
        case "el":
            return Collections.singleton(Character.UnicodeScript.GREEK);
        case "he": case "yi":
            return Collections.singleton(Character.UnicodeScript.HEBREW);
        case "ar": case "fa": case "ur":
            return Collections.singleton(Character.UnicodeScript.ARABIC);
        case "th":
            return Collections.singleton(Character.UnicodeScript.THAI);
        case "hi": case "mr": case "ne":
            return Collections.singleton(Character.UnicodeScript.DEVANAGARI);
        case "zh":
            return Collections.singleton(Character.UnicodeScript.HAN);
        case "ko":
            return new java.util.HashSet<>(Arrays.asList(
                Character.UnicodeScript.HANGUL, Character.UnicodeScript.HAN));
        case "ja":
            return new java.util.HashSet<>(Arrays.asList(
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA));
        default:
            return Collections.singleton(Character.UnicodeScript.LATIN);
        }
    }

    /**
     * Validates every external {@code messages_<lang>.properties} against the bundled
     * English base and returns a human-readable report. Community translators will
     * inevitably break {@code {0}} placeholders or MessageFormat's {@code ''}
     * apostrophe escaping — which fails at runtime, in front of users, in a language
     * the maintainer may not read. This catches it at startup instead.
     *
     * <p>Checks per file: how many base keys are translated, keys that don't exist in
     * the base (typos), MessageFormat syntax errors, and — for keys whose English has
     * placeholders — a render test with sentinel arguments that catches both missing
     * placeholders and placeholders accidentally quoted out by a lone apostrophe.
     */
    public static java.util.List<String> lintExternal()
    {
        java.util.List<String> report = new java.util.ArrayList<>();
        File dir = externalDir;
        if (dir == null || !dir.isDirectory())
        {
            report.add("No external translations folder is configured.");
            return report;
        }
        // The pure bundled base (the overlay-merged bundle("") would hide problems).
        Map<String, String> base = new LinkedHashMap<>();
        try (InputStream in = I18n.class.getResourceAsStream(BASE + ".properties"))
        {
            if (in != null)
                loadInto(base, in);
        }
        catch (Exception ex)
        {
            report.add("Could not load the bundled English base: " + ex);
            return report;
        }
        File[] files = dir.listFiles((d, name) -> name.startsWith("messages_") && name.endsWith(".properties"));
        if (files == null || files.length == 0)
        {
            report.add("No external messages_<lang>.properties files found in " + dir);
            return report;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files)
        {
            if (file.getName().contains("template"))
                continue;
            Map<String, String> ext = new LinkedHashMap<>();
            try (InputStream in = Files.newInputStream(file.toPath()))
            {
                loadInto(ext, in);
            }
            catch (Exception ex)
            {
                report.add(file.getName() + ": UNREADABLE - " + ex);
                continue;
            }
            int translated = 0, unknown = 0;
            java.util.List<String> problems = new java.util.ArrayList<>();
            for (Map.Entry<String, String> entry : ext.entrySet())
            {
                String key = entry.getKey(), value = entry.getValue(), en = base.get(key);
                if (en == null)
                {
                    unknown++;
                    if (unknown <= 5)
                        problems.add("unknown key (typo?): " + key);
                    continue;
                }
                translated++;
                String problem = lintValue(key, en, value);
                if (problem != null)
                    problems.add(problem);
            }
            report.add(String.format("%s: %d/%d keys translated, %d unknown, %d problem(s)",
                file.getName(), translated, base.size(), unknown, problems.size()));
            for (String problem : problems)
                report.add("    " + problem);
        }
        return report;
    }

    /** One key's lint verdict: null when fine, else a description of what's wrong. */
    static String lintValue(String key, String en, String value)
    {
        MessageFormat format;
        try
        {
            format = new MessageFormat(value);
        }
        catch (RuntimeException ex)
        {
            return key + ": invalid MessageFormat - " + ex.getMessage();
        }
        // Which placeholder indices does the English use?
        java.util.Set<Integer> indices = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)").matcher(en);
        while (m.find())
            indices.add(Integer.valueOf(m.group(1)));
        if (indices.isEmpty())
            return null;
        // Render with sentinels: catches placeholders that are missing entirely AND
        // placeholders silently quoted out by a lone apostrophe ('{0}' renders as {0}).
        int max = 0;
        for (Integer i : indices)
            max = Math.max(max, i.intValue());
        Object[] sentinels = new Object[max + 1];
        for (int i = 0; i <= max; i++)
            sentinels[i] = "§" + i + "§";
        String rendered;
        try
        {
            rendered = format.format(sentinels);
        }
        catch (RuntimeException ex)
        {
            return key + ": does not format - " + ex.getMessage();
        }
        for (Integer i : indices)
            if (!rendered.contains("§" + i + "§"))
                return key + ": placeholder {" + i + "} is missing or quoted out (check '' escaping)";
        return null;
    }

    /** Runs {@link #lintExternal()} and logs the report (problems at WARN). */
    public static void lintExternalAndLog()
    {
        try
        {
            for (String line : lintExternal())
                if (line.startsWith("    "))
                    LOG.warn("{}", line.trim());
                else
                    LOG.info("i18n: {}", line);
        }
        catch (Exception ex)
        {
            LOG.warn("Translation lint failed", ex);
        }
    }

    /** Sets the desktop UI locale; null restores the OS default. */
    public static void setLocale(Locale loc)
    {
        locale = loc == null ? Locale.getDefault() : loc;
    }

    /** The active desktop UI locale. */
    public static Locale getLocale()
    {
        return locale;
    }

    private I18n()
    {
        throw new UnsupportedOperationException();
    }
}
