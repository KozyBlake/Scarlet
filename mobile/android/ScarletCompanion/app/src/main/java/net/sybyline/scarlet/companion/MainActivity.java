package net.sybyline.scarlet.companion;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {
    static final String PREFS = "scarlet_companion";
    static final String KEY_PAIRING_JSON = "pairing_json";
    static final String KEY_INSTANCE_ID = "instance_id";
    static final String KEY_DIRECT_EVENT_ENDPOINTS = "direct_event_endpoints";
    static final String KEY_DIRECT_PAIR_ENDPOINTS = "direct_pair_endpoints";
    static final String KEY_RELAY_EVENT_ENDPOINT = "relay_event_endpoint";
    static final String KEY_PAIRING_SECRET = "pairing_secret";
    static final String KEY_FCM_TOKEN = "fcm_token";

    final ExecutorService io = Executors.newSingleThreadExecutor();
    TextView statusText;
    TextView instanceText;
    TextView endpointText;
    TextView fcmText;
    String fcmToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScarletNotifier.ensureChannels(this);
        initializeFirebase();
        requestNotificationPermission();
        requestBatteryOptimizationExemption();
        buildUi();
        loadSavedState();
        refreshFcmToken(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(Color.rgb(23, 23, 34));
        scroll.addView(root);

        TextView title = text("Scarlet Companion", 26, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = text("Scan Scarlet's pairing QR to receive alerts anywhere in the world. Notifications are delivered as long as Scarlet is running on your host machine.", 15, Color.rgb(210, 210, 220));
        subtitle.setPadding(0, 12, 0, 24);
        root.addView(subtitle);

        statusText = text("Status: starting", 16, Color.rgb(230, 230, 238));
        instanceText = text("Instance: none", 14, Color.rgb(190, 190, 205));
        endpointText = text("No endpoint", 12, Color.rgb(165, 165, 180));
        fcmText = text("", 12, Color.rgb(165, 165, 180));
        root.addView(statusText);
        root.addView(instanceText);
        root.addView(endpointText);
        root.addView(fcmText);

        root.addView(button("Scan Scarlet QR", v -> scanQr()));
        root.addView(button("Paste Pairing JSON", v -> showPasteDialog()));
        root.addView(button("Start Direct Listener", v -> startDirectListener()));
        root.addView(button("Stop Direct Listener", v -> stopService(new Intent(this, DirectScarletService.class))));
        root.addView(button("Show Test Local Notification", v ->
            ScarletNotifier.showAlert(this, "Scarlet Companion", "Local notification test", "test")));
        root.addView(button("Open Notification Settings", v -> openNotificationSettings()));
        root.addView(button("View Alert Log", v -> showAlertLog()));
        root.addView(button("Clear Alert Log", v -> { AlertLog.clear(this); toast("Alert log cleared."); }));

        setContentView(scroll);
    }

    TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setPadding(0, 6, 0, 6);
        return text;
    }

    Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 18, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    void scanQr() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan Scarlet mobile pairing QR");
        integrator.setBeepEnabled(true);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                pairFromJson(result.getContents());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    void showPasteDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        input.setHint("Paste Scarlet pairing JSON");
        new AlertDialog.Builder(this)
            .setTitle("Pair with Scarlet")
            .setView(input)
            .setPositiveButton("Pair", (dialog, which) -> pairFromJson(input.getText().toString()))
            .setNegativeButton("Cancel", null)
            .show();
    }

    void pairFromJson(String json) {
        try {
            JSONObject pairing = new JSONObject(json);
            JSONArray endpoints = pairing.optJSONArray("directEventEndpoints");
            if (endpoints == null || endpoints.length() == 0) {
                String single = clean(pairing.optString("directEventEndpoint", null));
                if (single != null) {
                    endpoints = new JSONArray();
                    endpoints.put(single);
                }
            }
            if (endpoints == null) endpoints = new JSONArray();
            boolean hasDirect = endpoints.length() > 0;
            boolean hasRelay = clean(pairing.optString("relayEndpoint", null)) != null;
            if (!hasDirect && !hasRelay) {
                throw new IllegalArgumentException("Pairing QR has no direct LAN or relay endpoint. Enable Mobile companion in Scarlet and regenerate the QR.");
            }

            prefs().edit()
                .putString(KEY_PAIRING_JSON, pairing.toString())
                .putString(KEY_DIRECT_EVENT_ENDPOINTS, endpoints.toString())
                .putString(KEY_DIRECT_PAIR_ENDPOINTS, directPairEndpoints(pairing).toString())
                .putString(KEY_RELAY_EVENT_ENDPOINT, clean(pairing.optString("relayEventEndpoint", null)))
                .putString(KEY_PAIRING_SECRET, clean(pairing.optString("pairingSecret", null)))
                .putString(KEY_INSTANCE_ID, pairing.optString("instanceId", ""))
                .apply();

            loadSavedState();
            registerWithRelay(pairing);
            if (hasDirect) {
                startDirectListener();
            } else {
                setStatus("Relay pairing saved. Push notifications active when app is closed.");
            }
            toast("Pairing saved.");
        } catch (Exception ex) {
            setStatus("Pairing failed: " + ex.getMessage());
            toast("Pairing failed.");
        }
    }

    boolean hasPairing() {
        if (hasDirectEndpoints()) return true;
        String relay = prefs().getString(KEY_RELAY_EVENT_ENDPOINT, null);
        return clean(relay) != null;
    }

    void startDirectListener() {
        if (!hasPairing()) {
            toast("Scan a Scarlet pairing QR first.");
            return;
        }
        requestNotificationPermission();
        Intent intent = new Intent(this, DirectScarletService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        setStatus("Direct listener running.");
    }

    void registerWithRelay(JSONObject pairing) {
        io.execute(() -> {
            try {
                JSONArray pairEndpoints = directPairEndpoints(pairing);
                String relayPairEndpoint = clean(pairing.optString("relayPairEndpoint", null));
                if (pairEndpoints.length() == 0 && relayPairEndpoint == null) return;

                JSONObject device = new JSONObject();
                device.put("name", Build.MANUFACTURER + " " + Build.MODEL);
                device.put("platform", "android");
                if (fcmToken != null && !fcmToken.trim().isEmpty())
                    device.put("pushToken", fcmToken);
                device.put("notificationTypes", pairing.optJSONObject("notificationDefaults"));

                JSONObject request = new JSONObject();
                request.put("pairingId", pairing.optString("pairingId"));
                request.put("pairingSecret", pairing.optString("pairingSecret"));
                request.put("instanceId", pairing.optString("instanceId"));
                request.put("device", device);

                // Try direct LAN pair endpoints first, then relay
                JSONArray allEndpoints = new JSONArray();
                for (int i = 0; i < pairEndpoints.length(); i++) allEndpoints.put(pairEndpoints.getString(i));
                if (relayPairEndpoint != null) allEndpoints.put(relayPairEndpoint);

                Exception last = null;
                for (int i = 0; i < allEndpoints.length(); i++) {
                    try {
                        JSONObject response = postJson(allEndpoints.getString(i), request);
                        if (response.optBoolean("ok", false)) {
                            runOnUiThread(() -> setStatus("Paired successfully."));
                            return;
                        }
                    } catch (Exception ex) {
                        last = ex;
                    }
                }
                if (last != null) throw last;
            } catch (Exception ex) {
                runOnUiThread(() -> setStatus("Pairing failed: " + ex.getMessage()));
            }
        });
    }

    JSONArray directPairEndpoints(JSONObject pairing) {
        JSONArray endpoints = pairing.optJSONArray("directPairEndpoints");
        if (endpoints != null && endpoints.length() > 0) return endpoints;
        String single = clean(pairing.optString("directPairEndpoint", null));
        endpoints = new JSONArray();
        if (single != null) endpoints.put(single);
        return endpoints;
    }

    JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + response);
        }
        return response == null || response.trim().isEmpty()
            ? new JSONObject().put("ok", true)
            : new JSONObject(response);
    }

    String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        }
        return out.toString();
    }

    void initializeFirebase() {
        String appId = getString(R.string.firebase_app_id);
        String apiKey = getString(R.string.firebase_api_key);
        String projectId = getString(R.string.firebase_project_id);
        String senderId = getString(R.string.firebase_sender_id);
        if (clean(appId) == null || clean(apiKey) == null || clean(projectId) == null || clean(senderId) == null) {
            return;
        }
        if (!FirebaseApp.getApps(this).isEmpty()) return;
        FirebaseOptions options = new FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setProjectId(projectId)
            .setGcmSenderId(senderId)
            .build();
        FirebaseApp.initializeApp(this, options);
    }

    void refreshFcmToken(Runnable after) {
        String cached = prefs().getString(KEY_FCM_TOKEN, null);
        if (cached != null && !cached.trim().isEmpty()) {
            fcmToken = cached;
            updateFcmText();
            if (after != null) after.run();
            return;
        }
        if (FirebaseApp.getApps(this).isEmpty()) {
            updateFcmText();
            if (after != null) after.run();
            return;
        }
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                fcmToken = task.getResult();
                prefs().edit().putString(KEY_FCM_TOKEN, fcmToken).apply();
            }
            updateFcmText();
            if (after != null) after.run();
        });
    }

    void updateFcmText() {
        if (fcmText == null) return;
        if (fcmToken != null && !fcmToken.trim().isEmpty()) {
            String preview = fcmToken.length() > 18 ? fcmToken.substring(0, 18) + "..." : fcmToken;
            fcmText.setText("FCM token: " + preview);
        } else {
            fcmText.setText("");
        }
    }

    boolean hasDirectEndpoints() {
        String endpoints = prefs().getString(KEY_DIRECT_EVENT_ENDPOINTS, "[]");
        try {
            return new JSONArray(endpoints).length() > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    void loadSavedState() {
        String instanceId = prefs().getString(KEY_INSTANCE_ID, "");
        String endpoints = prefs().getString(KEY_DIRECT_EVENT_ENDPOINTS, "[]");
        String relayEventEndpoint = prefs().getString(KEY_RELAY_EVENT_ENDPOINT, null);
        instanceText.setText(instanceId == null || instanceId.isEmpty()
            ? "Instance: none"
            : "Instance: " + instanceId);
        boolean hasDirect = hasDirectEndpoints();
        boolean hasRelay = clean(relayEventEndpoint) != null;
        if (hasDirect && hasRelay) {
            endpointText.setText("Direct + relay paired");
        } else if (hasDirect) {
            endpointText.setText("Direct: " + endpointSummary(endpoints));
        } else if (hasRelay) {
            endpointText.setText("Relay: " + relayEventEndpoint);
        } else {
            endpointText.setText("No endpoint");
        }
        setStatus(hasDirect || hasRelay ? "Ready." : "Not paired.");
    }

    String endpointSummary(String endpointsJson) {
        try {
            JSONArray endpoints = new JSONArray(endpointsJson);
            if (endpoints.length() == 0) return "none";
            String first = endpoints.getString(0);
            return endpoints.length() == 1 ? first : first + " +" + (endpoints.length() - 1);
        } catch (Exception ex) {
            return "none";
        }
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        startActivity(new Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + getPackageName())));
    }

    void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void setStatus(String value) {
        runOnUiThread(() -> statusText.setText("Status: " + value));
    }

    void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    void showAlertLog() {
        java.util.List<AlertLog.Entry> entries = AlertLog.load(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        scroll.addView(layout);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No alerts logged yet.");
            empty.setTextColor(Color.rgb(180, 180, 190));
            empty.setPadding(0, 16, 0, 16);
            layout.addView(empty);
        } else {
            for (AlertLog.Entry entry : entries) {
                int titleColor = colorForType(entry.eventType);

                TextView ts = new TextView(this);
                ts.setText(entry.timestamp);
                ts.setTextColor(Color.rgb(130, 130, 150));
                ts.setTextSize(11);
                ts.setPadding(0, 10, 0, 0);
                layout.addView(ts);

                TextView titleView = new TextView(this);
                titleView.setText(entry.title);
                titleView.setTextColor(titleColor);
                titleView.setTextSize(14);
                titleView.setPadding(0, 2, 0, 0);
                layout.addView(titleView);

                if (!entry.body.isEmpty()) {
                    TextView bodyView = new TextView(this);
                    bodyView.setText(entry.body);
                    bodyView.setTextColor(Color.rgb(210, 210, 225));
                    bodyView.setTextSize(13);
                    bodyView.setPadding(0, 2, 0, 0);
                    layout.addView(bodyView);
                }

                View divider = new View(this);
                divider.setBackgroundColor(Color.rgb(55, 55, 75));
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
                divider.setPadding(0, 8, 0, 0);
                layout.addView(divider);
            }
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("Alert Log (" + entries.size() + ")")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    }

    static int colorForType(String eventType) {
        if (eventType == null) return Color.rgb(200, 200, 215);
        switch (eventType) {
            case "watched_user_join":
            case "watched_group_join":
                return Color.rgb(214, 74, 104);  // Scarlet red — high priority
            case "watched_avatar":
            case "vote_to_kick":
                return Color.rgb(255, 165, 50);  // Orange — medium-high
            case "moderation":
                return Color.rgb(255, 210, 60);  // Yellow — moderation action
            case "suspicious_pronouns":
            case "mixed_character_name":
                return Color.rgb(120, 180, 255); // Blue — suspicious but informational
            case "staff":
                return Color.rgb(140, 220, 140); // Green — staff movement
            case "new_player":
                return Color.rgb(180, 140, 255); // Purple — new account
            case "test":
                return Color.rgb(160, 160, 175); // Muted — test only
            default:
                return Color.rgb(200, 200, 215);
        }
    }

    static String clean(String value) {
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
