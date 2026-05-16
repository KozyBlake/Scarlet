package net.sybyline.scarlet.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import net.gcardone.junidecode.Junidecode;

/**
 * Shared name-normalisation pipeline used by the VRChat help-desk URL builder
 * and the TTS service.
 *
 * <p>{@link #toVisualAscii(String)} handles Latin-looking confusables for
 * spoken moderation callouts. {@link #toAscii(String)} produces deterministic
 * ASCII transliteration. {@link #describeScripts(String)} names non-Latin
 * scripts for names that are not visually readable as Latin.
 */
public final class Names
{
    private Names() { throw new UnsupportedOperationException(); }

    static final Pattern CONTROL_OR_FORMAT  = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    static final Pattern DECORATIVE_NOISE   = Pattern.compile("[\\p{So}\\p{Sk}\\p{Cs}\\p{Co}\\p{Cn}]|[\\p{Po}&&[^\\x00-\\x7F]]");
    static final Pattern COMBINING_MARKS    = Pattern.compile("\\p{M}+");
    static final Pattern NON_ASCII          = Pattern.compile("[^\\x00-\\x7F]");
    static final Pattern JUNIDECODE_UNKNOWN = Pattern.compile("\\[\\?\\]");
    static final Pattern WHITESPACE_RUN     = Pattern.compile("\\s+");

    public static String toAscii(String name)
    {
        if (name == null || name.isEmpty()) return null;
        String n = CONTROL_OR_FORMAT.matcher(name).replaceAll("");
        n = DECORATIVE_NOISE.matcher(n).replaceAll("");
        n = mapStrokeLatin(n);
        n = Normalizer.normalize(n, Normalizer.Form.NFKD);
        n = COMBINING_MARKS.matcher(n).replaceAll("");
        if (NON_ASCII.matcher(n).find())
        {
            n = Junidecode.unidecode(n);
            n = JUNIDECODE_UNKNOWN.matcher(n).replaceAll("");
            n = COMBINING_MARKS.matcher(n).replaceAll("");
            n = NON_ASCII.matcher(n).replaceAll("");
        }
        n = WHITESPACE_RUN.matcher(n).replaceAll(" ").trim();
        return n.isEmpty() ? null : n;
    }

    public static String toAscii(String name, String fallback)
    {
        String ascii = toAscii(name);
        return ascii != null ? ascii : fallback;
    }

    public static String toVisualAscii(String name)
    {
        if (name == null || name.isEmpty()) return null;
        if (!hasLatinLetter(name))
            return null;
        String n = CONTROL_OR_FORMAT.matcher(name).replaceAll("");
        n = DECORATIVE_NOISE.matcher(n).replaceAll("");
        n = mapStrokeLatin(n);
        n = mapVisualLatinConfusables(n);
        n = Normalizer.normalize(n, Normalizer.Form.NFKD);
        n = COMBINING_MARKS.matcher(n).replaceAll("");
        if (NON_ASCII.matcher(n).find())
            return null;
        n = WHITESPACE_RUN.matcher(n).replaceAll(" ").trim();
        return n.isEmpty() ? null : n;
    }

    public static boolean hasLatinLetter(String name)
    {
        if (name == null || name.isEmpty()) return false;
        String n = CONTROL_OR_FORMAT.matcher(name).replaceAll("");
        n = mapStrokeLatin(n);
        n = Normalizer.normalize(n, Normalizer.Form.NFKD);
        for (int i = 0; i < n.length(); )
        {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            if (!isLetterLike(Character.getType(cp)))
                continue;
            try
            {
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN)
                    return true;
            }
            catch (IllegalArgumentException ex)
            {
            }
        }
        return false;
    }

    public static boolean hasMixedLetterScripts(String name)
    {
        if (name == null || name.isEmpty()) return false;
        Set<Character.UnicodeScript> scripts = new LinkedHashSet<>();
        for (int i = 0; i < name.length(); )
        {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (!isLetterLike(Character.getType(cp)))
                continue;
            Character.UnicodeScript script;
            try { script = Character.UnicodeScript.of(cp); }
            catch (IllegalArgumentException ex) { continue; }
            if (script == Character.UnicodeScript.COMMON
             || script == Character.UnicodeScript.INHERITED
             || script == Character.UnicodeScript.UNKNOWN) continue;
            scripts.add(script);
            if (scripts.size() > 1)
                return true;
        }
        return false;
    }

    private static boolean isLetterLike(int type)
    {
        switch (type)
        {
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.LETTER_NUMBER:
                return true;
            default:
                return false;
        }
    }

    public static String describeScripts(String name)
    {
        if (name == null || name.isEmpty()) return "";
        Set<String> scripts = new LinkedHashSet<>();
        for (int i = 0; i < name.length(); )
        {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT || type == Character.CONTROL || type == Character.SURROGATE) continue;
            Character.UnicodeScript script;
            try { script = Character.UnicodeScript.of(cp); }
            catch (IllegalArgumentException ex) { continue; }
            if (script == Character.UnicodeScript.LATIN
             || script == Character.UnicodeScript.COMMON
             || script == Character.UnicodeScript.INHERITED
             || script == Character.UnicodeScript.UNKNOWN) continue;
            String friendly = friendlyScriptName(script);
            if (friendly != null) scripts.add(friendly);
        }
        if (scripts.isEmpty()) return "";
        if (scripts.size() >= 3) return "mixed scripts";
        List<String> ordered = new ArrayList<>(scripts);
        if (ordered.size() == 1) return ordered.get(0);
        return ordered.get(0) + " and " + ordered.get(1);
    }

    private static String friendlyScriptName(Character.UnicodeScript script)
    {
        switch (script)
        {
            case CYRILLIC:   return "Cyrillic";
            case ARABIC:     return "Arabic";
            case HAN:        return "CJK";
            case HIRAGANA:   return "Japanese";
            case KATAKANA:   return "Japanese";
            case HANGUL:     return "Korean";
            case THAI:       return "Thai";
            case HEBREW:     return "Hebrew";
            case DEVANAGARI: return "Devanagari";
            case GREEK:      return "Greek";
            case GEORGIAN:   return "Georgian";
            case ARMENIAN:   return "Armenian";
            case MYANMAR:    return "Myanmar";
            case KHMER:      return "Khmer";
            case TIBETAN:    return "Tibetan";
            case ETHIOPIC:   return "Ethiopic";
            case SINHALA:    return "Sinhala";
            case TAMIL:      return "Tamil";
            case TELUGU:     return "Telugu";
            case KANNADA:    return "Kannada";
            case MALAYALAM:  return "Malayalam";
            case BENGALI:    return "Bengali";
            case GUJARATI:   return "Gujarati";
            case GURMUKHI:   return "Gurmukhi";
            case LAO:        return "Lao";
            case MONGOLIAN:  return "Mongolian";
            case CHEROKEE:   return "Cherokee";
            default:         return "non-Latin";
        }
    }

    static String mapStrokeLatin(String s)
    {
        StringBuilder out = null;
        for (int i = 0, n = s.length(); i < n; )
        {
            int cp = s.codePointAt(i);
            int cc = Character.charCount(cp);
            String repl = strokeLatinFor(cp);
            if (repl == null)
            {
                if (out != null)
                    out.appendCodePoint(cp);
            }
            else
            {
                if (out == null)
                {
                    out = new StringBuilder(s.length() + 4);
                    out.append(s, 0, i);
                }
                out.append(repl);
            }
            i += cc;
        }
        return out == null ? s : out.toString();
    }

    // Latin-Extended "letter with stroke/bar" glyphs that NFKD does not
    // decompose (the stroke is part of the character's identity, not a
    // combining mark) and that junidecode 0.5.2 has gaps for.
    static String strokeLatinFor(int cp)
    {
        switch (cp)
        {
        case 'Ø': return "O"; case 'ø': return "o";
        case 'Đ': return "D"; case 'đ': return "d";
        case 'Ħ': return "H"; case 'ħ': return "h";
        case 'Ł': return "L"; case 'ł': return "l";
        case 'Ŧ': return "T"; case 'ŧ': return "t";
        case 'ƀ': return "b";
        case 'Ƃ': return "B"; case 'ƃ': return "b";
        case 'Ⱥ': return "A";
        case 'Ȼ': return "C"; case 'ȼ': return "c";
        case 'Ƚ': return "L";
        case 'Ⱦ': return "T";
        case 'Ɇ': return "E"; case 'ɇ': return "e";
        case 'Ɉ': return "J"; case 'ɉ': return "j";
        case 'Ɋ': return "Q"; case 'ɋ': return "q";
        case 'Ɍ': return "R"; case 'ɍ': return "r";
        case 'Ɏ': return "Y"; case 'ɏ': return "y";
        case 'ɨ': return "i";
        case 'ʈ': return "t";
        case 'ᵻ': return "I"; case 'ᵾ': return "U";
        }
        return null;
    }

    // ── Visual-confusable mapping ──────────────────────────────────────────
    // Replaces non-Latin codepoints that visually impersonate ASCII Latin
    // letters (Cyrillic А, Greek Α, Cherokee Ꭺ, etc.) with their Latin
    // lookalike. Used by toVisualAscii so a namespace-dodge handle like
    // "Аpple" (Cyrillic А) reads aloud as "Apple" — exactly what the
    // moderator sees on the nameplate — instead of being transliterated as
    // a separate word.
    static String mapVisualLatinConfusables(String s)
    {
        StringBuilder out = null;
        for (int i = 0, n = s.length(); i < n; )
        {
            int cp = s.codePointAt(i);
            int cc = Character.charCount(cp);
            String repl = visualLatinFor(cp);
            if (repl == null)
            {
                if (out != null)
                    out.appendCodePoint(cp);
            }
            else
            {
                if (out == null)
                {
                    out = new StringBuilder(s.length() + 4);
                    out.append(s, 0, i);
                }
                out.append(repl);
            }
            i += cc;
        }
        return out == null ? s : out.toString();
    }

    static String visualLatinFor(int cp)
    {
        switch (cp)
        {
        // Cyrillic uppercase that visually impersonate Latin caps
        case 'А': return "A"; case 'В': return "B"; case 'Е': return "E";
        case 'З': return "3"; case 'І': return "I"; case 'Ј': return "J";
        case 'К': return "K"; case 'М': return "M";
        case 'Н': return "H"; // looks like H, not N (famous gotcha)
        case 'О': return "O";
        case 'Р': return "P"; // looks like P, not R (famous gotcha)
        case 'С': return "C"; case 'Т': return "T"; case 'У': return "Y";
        case 'Х': return "X"; case 'Ѕ': return "S"; case 'Ү': return "Y";
        case 'Ғ': return "F"; case 'Ԛ': return "Q"; case 'Ԝ': return "W";

        // Cyrillic lowercase
        case 'а': return "a"; case 'е': return "e"; case 'о': return "o";
        case 'р': return "p"; case 'с': return "c"; case 'у': return "y";
        case 'х': return "x"; case 'і': return "i"; case 'ј': return "j";
        case 'ѕ': return "s"; case 'ԛ': return "q"; case 'ԝ': return "w";
        case 'ӏ': return "l";

        // Greek uppercase
        case 'Α': return "A"; case 'Β': return "B"; case 'Ε': return "E";
        case 'Ζ': return "Z"; case 'Η': return "H"; case 'Ι': return "I";
        case 'Κ': return "K"; case 'Μ': return "M"; case 'Ν': return "N";
        case 'Ο': return "O"; case 'Ρ': return "P"; case 'Τ': return "T";
        case 'Υ': return "Y"; case 'Χ': return "X";

        // Greek lowercase that read clearly as Latin
        case 'ο': return "o"; case 'ν': return "v"; case 'α': return "a";
        case 'κ': return "k"; case 'ρ': return "p"; case 'ι': return "i";
        case 'τ': return "t";

        // Armenian
        case 'Օ': return "O"; case 'օ': return "o"; case 'հ': return "h";

        // Cherokee letters that look like Latin caps
        case 'Ꭰ': return "D"; case 'Ꭱ': return "R"; case 'Ꭲ': return "T";
        case 'Ꭺ': return "A"; case 'Ꭻ': return "J"; case 'Ꭼ': return "E";
        case 'Ꮃ': return "W"; case 'Ꮄ': return "L"; case 'Ꮋ': return "H";
        case 'Ꮍ': return "Y"; case 'Ꮤ': return "W";
        }
        return null;
    }
}

