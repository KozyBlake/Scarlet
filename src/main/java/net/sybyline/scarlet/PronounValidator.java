package net.sybyline.scarlet;

import java.util.regex.Pattern;

/**
 * Validates whether a VRChat pronouns field looks like a genuine pronoun set
 * or has been repurposed for a username, phrase, or troll content.
 *
 * <p>Checking order (first match wins):
 * <ol>
 *   <li>Null / empty → not flagged</li>
 *   <li>Found in {@code good_pronoun.json} → not flagged (user allow-list)</li>
 *   <li>Found in {@code bad_pronoun.json}  → flagged   (user deny-list)</li>
 *   <li>Heuristic rules (segment length, spaces, digits, character set) →
 *       flagged if any rule fires</li>
 * </ol>
 *
 * <p>Both JSON files live in Scarlet's data directory and are reloaded each
 * call via the {@link ScarletPronounLists} instance on {@link Scarlet}.
 * No restart is needed after editing the files.
 */
public final class PronounValidator
{

    private PronounValidator() {}

    /** Max characters per individual slash-separated token. */
    private static final int MAX_SEGMENT_LEN = 20;

    /** Max total length of the whole pronouns string. */
    private static final int MAX_TOTAL_LEN = 60;

    /** Max number of slash-separated segments. */
    private static final int MAX_SEGMENTS = 4;

    /**
     * A token is valid if it consists entirely of Unicode letters,
     * combining marks, apostrophes, or hyphens.
     */
    private static final Pattern VALID_TOKEN =
        Pattern.compile("[\\p{L}\\p{M}'\\-]{1," + MAX_SEGMENT_LEN + "}");

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the pronouns field should be flagged.
     * Reads the current good/bad lists from {@code Scarlet.pronounLists} on
     * every call so file edits take effect immediately without a restart.
     *
     * @param pronouns raw value from {@code user.getPronouns()}
     */
    public static boolean isFlagged(String pronouns)
    {
        return flagReason(pronouns) != null;
    }

    /**
     * Returns a short human-readable reason if the pronouns are flagged,
     * or {@code null} if they look legitimate.
     */
    public static String flagReason(String pronouns)
    {
        if (pronouns == null)
            return null;

        String trimmed = pronouns.trim();
        if (trimmed.isEmpty())
            return null;

        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);

        // ── User-maintained allow-list (good_pronoun.json) ─────────────────────
        ScarletPronounLists lists = lists();
        if (lists != null && lists.isKnownGood(lower))
            return null;

        // ── User-maintained deny-list (bad_pronoun.json) ───────────────────────
        if (lists != null && lists.isKnownBad(lower))
            return "Listed in bad_pronoun.json: \"" + trimmed + "\"";

        // ── Heuristic rules ────────────────────────────────────────────────────

        if (trimmed.length() > MAX_TOTAL_LEN)
            return "Pronouns field is unusually long (" + trimmed.length() + " chars)";

        String[] parts = trimmed.split("/", -1);

        if (parts.length > MAX_SEGMENTS)
            return "Pronouns field has too many segments (" + parts.length + ")";

        for (String part : parts)
        {
            String token = part.trim();

            if (token.isEmpty())
                return "Pronouns field has an empty segment";

            if (token.length() > MAX_SEGMENT_LEN)
                return "Pronoun segment \"" + token + "\" is too long";

            if (token.indexOf(' ') >= 0)
                return "Pronoun segment \"" + token + "\" contains a space";

            if (token.chars().anyMatch(Character::isDigit))
                return "Pronoun segment \"" + token + "\" contains a digit";

            if (!VALID_TOKEN.matcher(token).matches())
                return "Pronoun segment \"" + token + "\" contains invalid characters";
        }

        return null;
    }

    // ── Internal helper ────────────────────────────────────────────────────────

    /**
     * Returns the shared {@link ScarletPronounLists} from the running Scarlet
     * instance, or {@code null} if Scarlet hasn't fully started yet.
     * Failing gracefully here means the validator still works during startup.
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
