package net.sybyline.scarlet.util;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Free-text machine translation for advisory prose, with a zero-setup default so it works for
 * users who won't run their own server.
 *
 * <p>Two backends, chosen automatically by whether an endpoint is configured:
 * <ul>
 *   <li><b>Built-in (default, no configuration):</b> the free <a href="https://mymemory.translated.net/">MyMemory</a>
 *       API — no sign-up, no API key, roughly 5,000 characters/day per IP (50,000 if an email is
 *       supplied). Used when no endpoint is set, so pressing Translate just works.</li>
 *   <li><b>LibreTranslate (optional):</b> if a LibreTranslate-compatible endpoint URL <em>is</em>
 *       set, that is used instead — self-host it for full privacy (advisory text never leaves the
 *       machine) or point at any instance. Auto-detects the source language.</li>
 * </ul>
 *
 * <p>Only the advisory prose a list author wrote is ever sent; group/user/avatar names, ids and
 * tags are never passed here.
 */
public final class Translator
{
    static final Logger LOG = LoggerFactory.getLogger("Scarlet/Translator");
    private static final Gson GSON = new Gson();

    // A browser-like UA: some hosts 403 non-browser User-Agents (the HTTP helper's default is
    // Scarlet's own UA). MyMemory doesn't need this, but LibreTranslate mirrors behind a WAF often do.
    private static final String BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // MyMemory limits each request's q to 500 bytes; stay safely under that and split long text.
    private static final int MYMEMORY_MAX_BYTES = 460;

    private Translator() {}

    /** Translation is always available now (the built-in free service needs no configuration). */
    public static boolean isConfigured(String endpoint)
    {
        return true;
    }

    /**
     * Translates {@code text} into {@code targetLang} (a 2-letter code like {@code "de"}). If
     * {@code endpoint} is blank the free built-in MyMemory service is used (here {@code apiKey}
     * may optionally be an email to raise the daily limit); otherwise the LibreTranslate endpoint
     * at {@code endpoint} is used (here {@code apiKey} is its optional API key). Returns the
     * original text unchanged when the input is blank.
     *
     * @throws IOException if the service is unreachable or returns an error, so callers can show it.
     */
    public static String translate(String endpoint, String apiKey, String targetLang, String text) throws IOException
    {
        if (text == null || text.trim().isEmpty() || targetLang == null || targetLang.isEmpty())
            return text;
        if (endpoint != null && !endpoint.trim().isEmpty())
            return translateLibre(endpoint, apiKey, targetLang, text);
        return translateMyMemory(apiKey, targetLang, text);
    }

    // ── Built-in free backend: MyMemory ─────────────────────────────────────────
    // GET https://api.mymemory.translated.net/get?q=..&langpair=en|<target>[&de=email]
    // Assumes an English source (advisory lists are overwhelmingly English); for other source
    // languages, configure a LibreTranslate endpoint instead (it auto-detects). Long text is split
    // to respect the 500-byte-per-request limit, then reassembled.
    private static String translateMyMemory(String email, String targetLang, String text) throws IOException
    {
        if ("en".equalsIgnoreCase(targetLang))
            return text; // source is English; nothing to do (and MyMemory rejects en|en)
        StringBuilder outAll = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int li = 0; li < lines.length; li++)
        {
            if (li > 0) outAll.append('\n');
            String line = lines[li];
            if (line.trim().isEmpty())
            {
                outAll.append(line);
                continue;
            }
            List<String> chunks = splitByBytes(line, MYMEMORY_MAX_BYTES);
            for (int ci = 0; ci < chunks.size(); ci++)
            {
                if (ci > 0) outAll.append(' ');
                outAll.append(myMemoryGet(email, targetLang, chunks.get(ci)));
            }
        }
        return outAll.toString();
    }

    private static String myMemoryGet(String email, String targetLang, String chunk) throws IOException
    {
        String url = "https://api.mymemory.translated.net/get?q=" + enc(chunk)
                   + "&langpair=en%7C" + enc(targetLang);
        if (email != null && !email.trim().isEmpty())
            url += "&de=" + enc(email.trim());
        try (HttpURLInputStream in = HttpURLInputStream.of(url, "GET",
                (java.net.HttpURLConnection conn) ->
                {
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("User-Agent", BROWSER_UA);
                }, null))
        {
            JsonObject resp = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            int status = 200;
            if (resp != null && resp.has("responseStatus") && !resp.get("responseStatus").isJsonNull())
            {
                try { status = resp.get("responseStatus").getAsInt(); }
                catch (Exception ignored) { try { status = Integer.parseInt(resp.get("responseStatus").getAsString().trim()); } catch (Exception ignored2) {} }
            }
            if (status != 200)
                throw new IOException(resp != null && resp.has("responseDetails") && !resp.get("responseDetails").isJsonNull()
                    ? resp.get("responseDetails").getAsString() : ("MyMemory returned status " + status));
            if (resp != null && resp.has("responseData") && resp.get("responseData").isJsonObject())
            {
                JsonObject rd = resp.getAsJsonObject("responseData");
                if (rd.has("translatedText") && !rd.get("translatedText").isJsonNull())
                    return rd.get("translatedText").getAsString();
            }
            throw new IOException("MyMemory returned no translatedText");
        }
    }

    // Splits s into pieces each at most maxBytes UTF-8 bytes, breaking at spaces where possible so
    // words stay intact; a single over-long word is hard-split by codepoint as a last resort.
    static List<String> splitByBytes(String s, int maxBytes)
    {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (String word : s.split(" "))
        {
            int wordBytes = word.getBytes(StandardCharsets.UTF_8).length;
            if (wordBytes > maxBytes)
            {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); curBytes = 0; }
                StringBuilder w = new StringBuilder();
                int wb = 0;
                for (int i = 0; i < word.length(); )
                {
                    int cp = word.codePointAt(i);
                    i += Character.charCount(cp);
                    int cpBytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                    if (wb + cpBytes > maxBytes && w.length() > 0) { out.add(w.toString()); w.setLength(0); wb = 0; }
                    w.appendCodePoint(cp);
                    wb += cpBytes;
                }
                if (w.length() > 0) out.add(w.toString());
                continue;
            }
            int add = wordBytes + (cur.length() > 0 ? 1 : 0);
            if (curBytes + add > maxBytes) { out.add(cur.toString()); cur.setLength(0); curBytes = 0; }
            if (cur.length() > 0) { cur.append(' '); curBytes++; }
            cur.append(word);
            curBytes += wordBytes;
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add(s);
        return out;
    }

    private static String enc(String s)
    {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception ex) { return s; }
    }

    // ── Optional backend: LibreTranslate ────────────────────────────────────────
    private static String translateLibre(String endpoint, String apiKey, String targetLang, String text) throws IOException
    {
        String base = endpoint.trim();
        if (!base.matches("(?i)^https?://.*"))
            base = "https://" + base;
        while (base.endsWith("/"))
            base = base.substring(0, base.length() - 1);
        String url = base.endsWith("/translate") ? base : base + "/translate";

        JsonObject req = new JsonObject();
        req.addProperty("q", text);
        req.addProperty("source", "auto");
        req.addProperty("target", targetLang);
        req.addProperty("format", "text");
        if (apiKey != null && !apiKey.trim().isEmpty())
            req.addProperty("api_key", apiKey.trim());
        final byte[] body = GSON.toJson(req).getBytes(StandardCharsets.UTF_8);

        try (HttpURLInputStream in = HttpURLInputStream.post(url,
                (java.net.HttpURLConnection conn) ->
                {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("User-Agent", BROWSER_UA);
                },
                (OutputStream out) -> out.write(body)))
        {
            JsonObject resp = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (resp != null && resp.has("translatedText") && !resp.get("translatedText").isJsonNull())
                return resp.get("translatedText").getAsString();
            throw new IOException("Translation endpoint returned no translatedText");
        }
    }
}
