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
            case 0x0410: return "A"; case 0x0430: return "a";
            case 0x0412: return "B"; case 0x0432: return "b";
            case 0x0415: return "E"; case 0x0435: return "e";
            case 0x041A: return "K"; case 0x043A: return "k";
            case 0x041C: return "M"; case 0x043C: return "m";
            case 0x041D: return "H"; case 0x043D: return "h";
            case 0x041E: return "O"; case 0x043E: return "o";
            case 0x0420: return "P"; case 0x0440: return "p";
            case 0x0421: return "C"; case 0x0441: return "c";
            case 0x0422: return "T"; case 0x0442: return "t";
            case 0x0423: return "Y"; case 0x0443: return "y";
            case 0x0425: return "X"; case 0x0445: return "x";
            case 0x0405: return "S"; case 0x0455: return "s";
            case 0x0406: return "I"; case 0x0456: return "i";
            case 0x0408: return "J"; case 0x0458: return "j";
            case 0x04C0: return "I"; case 0x04CF: return "l";
            case 0x0391: return "A"; case 0x03B1: return "a";
            case 0x0392: return "B"; case 0x03B2: return "b";
            case 0x0395: return "E"; case 0x03B5: return "e";
            case 0x0396: return "Z"; case 0x03B6: return "z";
            case 0x0397: return "H"; case 0x03B7: return "n";
            case 0x0399: return "I"; case 0x03B9: return "i";
            case 0x039A: return "K"; case 0x03BA: return "k";
            case 0x039C: return "M"; case 0x03BC: return "u";
            case 0x039D: return "N"; case 0x03BD: return "v";
            case 0x039F: return "O"; case 0x03BF: return "o";
            case 0x03A1: return "P"; case 0x03C1: return "p";
            case 0x03A4: return "T"; case 0x03C4: return "t";
            case 0x03A5: return "Y"; case 0x03C5: return "u";
            case 0x03A7: return "X"; case 0x03C7: return "x";
            case 0x03D2: return "Y"; case 0x03D5: return "f";
            case 0x03D6: return "p"; case 0x03F2: return "c";
            case 0x03F3: return "j"; case 0x03F9: return "C";
            // Armenian/Georgian/Cherokee letters that are commonly used as
            // Latin-looking substitutions in otherwise Latin display names.
            case 0x0555: return "O"; case 0x0585: return "o";
            case 0x10D0: return "a"; case 0x10FF: return "o";
            case 0x13A0: return "D"; case 0x13A1: return "R";
            case 0x13A2: return "T"; case 0x13A5: return "i";
            case 0x13A9: return "Y"; case 0x13AA: return "A";
            case 0x13AB: return "J"; case 0x13B0: return "G";
            case 0x13B3: return "W"; case 0x13B7: return "M";
            case 0x13BB: return "H"; case 0x13BE: return "O";
            case 0x13C0: return "O"; case 0x13C2: return "P";
            case 0x13C3: return "S"; case 0x13C7: return "V";
            case 0x13D2: return "R";
            case 0x13D4: return "W"; case 0x13D5: return "S";
            case 0x13E2: return "L"; case 0x13E6: return "K";
            case 0x13E7: return "d"; case 0x13E9: return "V";
            case 0x13EC: return "b"; case 0x13F0: return "P";
            case 0x13F3: return "G";
        }
        return null;
    }

    static String strokeLatinFor(int cp)
    {
        switch (cp)
        {
            case 0x00D8: return "O"; case 0x00F8: return "o";
            case 0x0110: return "D"; case 0x0111: return "d";
            case 0x0126: return "H"; case 0x0127: return "h";
            case 0x0141: return "L"; case 0x0142: return "l";
            case 0x0166: return "T"; case 0x0167: return "t";
            case 0x0180: return "b";
            case 0x0182: return "B"; case 0x0183: return "b";
            case 0x023A: return "A";
            case 0x023B: return "C"; case 0x023C: return "c";
            case 0x023D: return "L";
            case 0x023E: return "T";
            case 0x0246: return "E"; case 0x0247: return "e";
            case 0x0248: return "J"; case 0x0249: return "j";
            case 0x024A: return "Q"; case 0x024B: return "q";
            case 0x024C: return "R"; case 0x024D: return "r";
            case 0x024E: return "Y"; case 0x024F: return "y";
            case 0x0268: return "i";
            case 0x0288: return "t";
            case 0x1D7B: return "I"; case 0x1D7E: return "U";
        }
        return null;
    }
}
