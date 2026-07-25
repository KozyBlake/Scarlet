package net.sybyline.scarlet.companion;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
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
    ConnectivityManager.NetworkCallback networkCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        ScarletNotifier.ensureChannels(this);
        running = true;
        startForeground(NOTIFICATION_ID, ScarletNotifier.serviceNotification(this, "Connecting to Scarlet..."));
        registerNetworkCallback();
    }

    /**
     * Reconnects the moment the phone regains a network instead of waiting out the backoff.
     * Coming back onto Wi-Fi is exactly when a moderator most needs alerts flowing again, and
     * without this the listener could sit idle for up to a minute after the network returned.
     */
    void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Thread w = worker;
                    // Wakes the reconnect sleep; a blocked socket read is unaffected.
                    if (w != null && w.isAlive()) w.interrupt();
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
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
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            networkCallback = null;
        }
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Scarlet writes a heartbeat comment every 15 seconds, so silence for appreciably longer
     * than that means the connection is dead even though the socket still looks open. Android,
     * carrier NAT and home routers all drop idle TCP without telling either end; previously the
     * read timeout was infinite, so the listener would block forever on a corpse of a
     * connection and every alert vanished until something else forced a reconnect.
     */
    static final int READ_TIMEOUT_MILLIS = 45000;
    static final long RECONNECT_MIN_MILLIS = 1000L;
    static final long RECONNECT_MAX_MILLIS = 60000L;
    /** A connection that survived at least this long counts as healthy, so backoff resets. */
    static final long CONNECTION_HEALTHY_MILLIS = 30000L;

    void runLoop() {
        long backoff = RECONNECT_MIN_MILLIS;
        while (running) {
            String[] endpoints = endpoints();
            if (endpoints.length == 0) {
                sleep(5000L);
                continue;
            }
            boolean connected = false;
            long startedAt = System.currentTimeMillis();
            for (String endpoint : endpoints) {
                if (!running) return;
                startedAt = System.currentTimeMillis();
                connected = listen(endpoint);
                if (connected) break;
            }
            if (!running) return;
            // Reset the backoff after a connection that actually held, otherwise grow it. This
            // stops a phone from hammering an unreachable PC (and burning battery) every second
            // while it is off the LAN, without slowing down a genuine brief reconnect. Duration
            // rather than the return value is the test: a stream that ran for an hour and then
            // hit the read timeout was healthy, even though it ends by throwing.
            if (System.currentTimeMillis() - startedAt >= CONNECTION_HEALTHY_MILLIS) {
                backoff = RECONNECT_MIN_MILLIS;
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2L, RECONNECT_MAX_MILLIS);
        }
    }

    boolean listen(String endpoint) {
        HttpURLConnection conn = null;
        try {
            startForeground(NOTIFICATION_ID, ScarletNotifier.serviceNotification(this, "Listening to Scarlet"));
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/event-stream");
            String secret = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_PAIRING_SECRET, null);
            if (secret != null && !secret.isEmpty())
                conn.setRequestProperty("Authorization", "Bearer " + secret);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(READ_TIMEOUT_MILLIS);
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
            String type = clean(event.optString("type", null));
            ScarletNotifier.showAlert(
                this,
                title == null ? "Scarlet alert" : title,
                body == null ? "Scarlet sent an alert." : body,
                id,
                type);
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
