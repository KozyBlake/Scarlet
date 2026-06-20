package net.sybyline.scarlet;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates whether a VRChat pronouns field looks like a genuine pronoun set
 * or has been repurposed for abuse, a link, or troll content.
 *
 * <p>The previous version flagged anything that wasn't an exact, clean
 * slash-separated token set, which produced a flood of false positives on
 * harmless fields like {@code "she/her puppy"} or {@code "any pronouns"} while
 * still letting short abusive fields through. This version is built around two
 * ideas:
 *
 * <ol>
 *   <li><b>Recognise real pronouns.</b> If the field <i>contains</i> any token
 *       from a broad list of known pronouns — binary, singular-they, and the
 *       common neopronoun sets (xe, ze/hir, ze/zir, fae, ey, e/Spivak, ae, co,
 *       hu, ne, per, thon, ve, vi, zhe, ki, …) — it is treated as legitimate
 *       even when extra words sit next to it. {@code "she/her puppy"} passes
 *       because {@code she} and {@code her} are recognised.</li>
 *   <li><b>Always flag genuine abuse.</b> Self-harm / violence directives
 *       ({@code "kill/yourself"}, {@code "kys"}, …), deny-listed strings, and
 *       links / Discord invites are flagged <i>first</i>, so they trip even if
 *       a real pronoun is also present (e.g. {@code "she/kys"}).</li>
 * </ol>
 *
 * <p>Checking order (first match wins):
 * <ol>
 *   <li>Null / empty / whitespace → not flagged.</li>
 *   <li>User allow-list ({@code good_pronoun.json}, whole-field match) → not flagged.</li>
 *   <li>Built-in abusive content (self-harm, violence) → flagged.</li>
 *   <li>User deny-list ({@code bad_pronoun.json}, substring match) → flagged.</li>
 *   <li>Embedded link / invite → flagged.</li>
 *   <li>Contains a recognised pronoun token → not flagged (extra words allowed).</li>
 *   <li>Conservative structural fallback (absurd length / segment count) → flagged.</li>
 *   <li>Otherwise → not flagged.</li>
 * </ol>
 *
 * <p>Both JSON lists live in Scarlet's data directory and are reloaded each
 * call via the {@link ScarletPronounLists} instance on {@link Scarlet}, so file
 * edits take effect immediately without a restart.
 */
public final class PronounValidator
{

    private PronounValidator() {}

    // ── Structural guard rails (only consulted for the fallback path) ───────────

    /** Max total length before a no-pronoun field is treated as a phrase/dump. */
    private static final int MAX_TOTAL_LEN = 80;

    /** Max slash/space/comma-separated segments for a no-pronoun field. */
    private static final int MAX_SEGMENTS = 6;

    /** Splits a field into candidate word tokens for recognition. */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}]+");

    /** Splits a field into "segments" the way a pronoun set is written. */
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("[/\\\\,&|]+");

    /** Detects an embedded URL or Discord invite in a pronouns field. */
    private static final Pattern LINK = Pattern.compile(
        "(?i)(https?://|www\\.|discord\\.(gg|com/invite)|t\\.me/|\\b[\\w-]+\\.(com|net|org|gg|io|xyz|me)\\b)");

    // ── Recognised pronoun tokens ───────────────────────────────────────────────
    // A field that contains ANY of these tokens is accepted (unless it also
    // contains abuse, a deny-listed string, or a link). Lowercase, ASCII.

    private static final Set<String> PRONOUN_TOKENS = new HashSet<>(Arrays.asList(
        // she / he / they / it / one
        "she", "her", "hers", "herself",
        "he", "him", "his", "himself",
        "they", "them", "their", "theirs", "themself", "themselves",
        "it", "its", "itself",
        "one", "ones", "oneself",
        // ae / æ
        "ae", "aer", "aers", "aerself",
        // ey (Elverson) + e (Spivak)
        "ey", "em", "eir", "eirs", "emself", "eyself",
        "e", "es", "em's",
        // fae
        "fae", "faer", "faers", "faeself",
        // xe
        "xe", "xem", "xyr", "xyrs", "xemself", "xir", "xirs", "xis", "xeself",
        // ze / zie + hir / zir
        "ze", "zie", "zir", "zirs", "zirself", "hir", "hirs", "hirself",
        "zem", "zes", "zeself", "zhe", "zher", "zhers",
        // co
        "co", "cos", "coself",
        // hu (humanist)
        "hu", "hum", "hus", "humself",
        // ne
        "ne", "nem", "nir", "nirs", "nemself", "nis",
        // per (person)
        "per", "pers", "perself",
        // thon
        "thon", "thons", "thonself",
        // ve / vi
        "ve", "ver", "vers", "verself", "vis",
        "vi", "vir", "virs", "vim", "vims",
        // ki (kinship)
        "ki", "kin", "kins",
        // meta / catch-all answers people actually write
        "any", "all", "ask", "none", "name", "names", "null", "nil",
        "mirror", "pronoun", "pronouns", "neopronouns"
    ));

    // ── Built-in abusive content ────────────────────────────────────────────────
    // Checked against a leet-folded, letter-only collapse of the whole field, so
    // separators and basic character-swaps don't hide it. Slurs are intentionally
    // NOT hardcoded here — those live in the user-editable bad_pronoun.json so the
    // list can be curated without recompiling.

    private static final String[] ABUSE_SUBSTRINGS = {
        "killyourself", "killyourselves", "killurself", "killyaself",
        "gokillyourself", "kysnow", "neckyourself", "neckurself",
        "hangyourself", "hangurself", "endyourself", "endurlife",
        "slityourwrist", "slittyourwrist", "drinkbleach", "godie", "godrown",
        "ropeyourself"
    };

    private static final Set<String> ABUSE_TOKENS = new HashSet<>(Arrays.asList(
        "kys", "kms", "kysw"
    ));

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns {@code true} if the pronouns field should be flagged. */
    public static boolean isFlagged(String pronouns)
    {
        return flagReason(pronouns) != null;
    }

    /**
     * Returns a short human-readable reason if the pronouns are flagged, or
     * {@code null} if they look legitimate. The raw value is intentionally not
     * echoed back here; callers that want it (TTS, Discord) already have it.
     *
     * @param pronouns raw value from {@code user.getPronouns()}
     */
    public static String flagReason(String pronouns)
    {
        if (pronouns == null)
            return null;

        String trimmed = pronouns.trim();
        if (trimmed.isEmpty())
            return null;

        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        ScarletPronounLists lists = lists();

        // 1. Whole-field allow-list wins outright (manual false-positive suppression).
        if (lists != null && lists.isKnownGood(lower))
            return null;

        // 2. Abuse always flags — even if a real pronoun is also present.
        String folded = leetFold(trimmed);
        for (String bad : ABUSE_SUBSTRINGS)
            if (folded.contains(bad))
                return "abusive or self-harm content";
        for (String tok : tokens(lower))
            if (ABUSE_TOKENS.contains(tok))
                return "abusive or self-harm content";

        // 3. User deny-list (substring, so it catches embedded entries).
        if (lists != null && lists.containsKnownBad(lower))
            return "matches deny-list";

        // 4. Links / invites don't belong in a pronouns field.
        if (LINK.matcher(trimmed).find())
            return "contains a link or invite";

        // 5. Contains a recognised pronoun → legitimate, extra words allowed.
        for (String tok : tokens(lower))
            if (PRONOUN_TOKENS.contains(tok))
                return null;

        // 6. No recognised pronoun: only flag clearly abnormal shapes, so
        //    harmless creative fields aren't false-flagged.
        if (trimmed.length() > MAX_TOTAL_LEN)
            return "unusually long for a pronouns field";

        String[] segments = SEGMENT_SPLIT.split(trimmed);
        if (segments.length > MAX_SEGMENTS)
            return "too many segments to be a pronoun set";

        // 7. Otherwise leave it alone.
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Splits a field into lowercase letter-only word tokens. */
    private static Set<String> tokens(String lower)
    {
        Set<String> out = new HashSet<>();
        for (String t : TOKEN_SPLIT.split(lower))
            if (!t.isEmpty())
                out.add(t);
        return out.isEmpty() ? Collections.emptySet() : out;
    }

    /**
     * Collapses a string to lowercase ASCII letters only, mapping common
     * leetspeak so simple obfuscation ("k1ll y0urs3lf") still matches the
     * abuse substrings.
     */
    private static String leetFold(String s)
    {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = Character.toLowerCase(s.charAt(i));
            switch (c)
            {
                case '0': c = 'o'; break;
                case '1': case '!': case '|': c = 'i'; break;
                case '3': c = 'e'; break;
                case '4': case '@': c = 'a'; break;
                case '5': case '$': c = 's'; break;
                case '7': c = 't'; break;
                default: break;
            }
            if (c >= 'a' && c <= 'z')
                sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Returns the shared {@link ScarletPronounLists} from the running Scarlet
     * instance, or {@code null} if Scarlet hasn't fully started yet.
     */
    private static ScarletPronounLists lists()
    {
        try
        {
            return Scarlet.pronounLists;
        }
        catch (Exception ex)
        {
            return null;
        }
    }

}
