package net.sybyline.scarlet.companion;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DirectScarletService extends Service {
    static final int NOTIFICATION_ID = 1001;

    volatile boolean running;
    Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        ScarletNotifier.ensureChannels(this);
        running = true;
        startForeground(NOTIFICATION_ID, ScarletNotifier.serviceNotification(this, "Connecting to Scarlet..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::runLoop, "Scarlet direct listener");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    void runLoop() {
        while (running) {
            String[] endpoints = endpoints();
            if (endpoints.length == 0) {
                sleep(5000L);
                continue;
            }
            boolean connected = false;
            for (String endpoint : endpoints) {
                if (!running) return;
                connected = listen(endpoint);
                if (connected) break;
            }
            // Shorter retry for relay (internet), longer for LAN misses
            sleep(connected ? 1000L : 5000L);
        }
    }

    boolean listen(String endpoint) {
        HttpURLConnection conn = null;
        try {
            startForeground(NOTIFICATION_ID, ScarletNotifier.serviceNotification(this, "Listening to Scarlet"));
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(0);
            int code = conn.getResponseCode();
            if (code != 200) {
                return false;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String eventType = null;
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    } else if (line.isEmpty()) {
                        if (data.length() > 0 && !"hello".equals(eventType)) {
                            handleEvent(data.toString());
                        }
                        data.setLength(0);
                        eventType = null;
                    }
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            startForeground(NOTIFICATION_ID, ScarletNotifier.serviceNotification(this, "Reconnecting to Scarlet..."));
        }
    }

    void handleEvent(String data) {
        try {
            JSONObject event = new JSONObject(data);
            String title = clean(event.optString("title", null));
            String body = clean(event.optString("body", null));
            String id = clean(event.optString("id", null));
            ScarletNotifier.showAlert(
                this,
                title == null ? "Scarlet alert" : title,
                body == null ? "Scarlet sent an alert." : body,
                id);
        } catch (Exception ignored) {
        }
    }

    String[] endpoints() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        java.util.List<String> result = new java.util.ArrayList<>();
        // Direct LAN endpoints first (lower latency when on same network)
        String directJson = prefs.getString(MainActivity.KEY_DIRECT_EVENT_ENDPOINTS, "[]");
        try {
            JSONArray array = new JSONArray(directJson);
            for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        } catch (Exception ignored) {}
        // Relay (internet) endpoint as fallback
        String relay = prefs.getString(MainActivity.KEY_RELAY_EVENT_ENDPOINT, null);
        if (relay != null && !relay.trim().isEmpty()) result.add(relay.trim());
        return result.toArray(new String[0]);
    }

    void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }

    static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
