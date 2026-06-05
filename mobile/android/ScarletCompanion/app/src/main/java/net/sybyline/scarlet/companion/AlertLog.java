package net.sybyline.scarlet.companion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Persistent log of every Scarlet alert received, stored in SharedPreferences.
 * Survives app restarts. Capped at MAX_ENTRIES to avoid unbounded growth.
 */
final class AlertLog {
    static final String PREFS_KEY = "alert_log";
    static final int MAX_ENTRIES = 500;

    static final SimpleDateFormat DATE_FORMAT =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    static class Entry {
        final String timestamp;
        final String title;
        final String body;
        final String eventType;

        Entry(String timestamp, String title, String body, String eventType) {
            this.timestamp = timestamp;
            this.title = title;
            this.body = body;
            this.eventType = eventType;
        }
    }

    static void append(Context context, String title, String body, String eventType) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(PREFS_KEY, "[]");
        try {
            JSONArray array = new JSONArray(existing);
            JSONObject entry = new JSONObject();
            entry.put("timestamp", DATE_FORMAT.format(new Date()));
            entry.put("title", title != null ? title : "Scarlet alert");
            entry.put("body", body != null ? body : "");
            entry.put("type", eventType != null ? eventType : "");
            array.put(entry);
            // Trim to max entries (keep most recent)
            while (array.length() > MAX_ENTRIES)
                array.remove(0);
            prefs.edit().putString(PREFS_KEY, array.toString()).apply();
        } catch (Exception ignored) {}
    }

    static List<Entry> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(PREFS_KEY, "[]");
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = array.length() - 1; i >= 0; i--) {
                JSONObject obj = array.getJSONObject(i);
                entries.add(new Entry(
                    obj.optString("timestamp", ""),
                    obj.optString("title", ""),
                    obj.optString("body", ""),
                    obj.optString("type", "")));
            }
        } catch (Exception ignored) {}
        return entries;
    }

    static void clear(Context context) {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .edit().remove(PREFS_KEY).apply();
    }
}
