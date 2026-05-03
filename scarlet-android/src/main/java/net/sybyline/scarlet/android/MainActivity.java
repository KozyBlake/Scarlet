package net.sybyline.scarlet.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.time.format.DateTimeFormatter;

/**
 * The app's single screen.
 *
 * <p>Two-step setup:
 *
 * <ol>
 *   <li>First launch: <em>Pair</em> screen.  User enables Wireless
 *       Debugging on the phone, taps "Pair device with pairing code",
 *       enters the 6-digit code into Scarlet, and taps <em>Pair</em>.
 *       Scarlet runs the SPAKE2 handshake against the loopback adbd via
 *       {@link AdbPairingService#pair(String, AdbPairingService.Callback)};
 *       on success a sticky flag is persisted so this screen never shows
 *       again.</li>
 *   <li>Every launch after that: <em>Connect</em> screen.  Scarlet opens
 *       a TLS session to adbd's {@code _adb-tls-connect._tcp} via
 *       {@link AdbPairingService#connect(AdbPairingService.Callback)},
 *       hands it to {@link ScarletLogService}, and starts mirroring
 *       VRChat's log lines.</li>
 * </ol>
 *
 * <p>If Wireless Debugging is off when the user comes back to the app,
 * the connect step surfaces a clear error and the user just toggles WD
 * back on - no PC, no re-pair.
 */
public final class MainActivity extends Activity {

    /** SharedPreferences slot we set after the first successful pair. */
    private static final String PREFS = "scarlet";
    private static final String KEY_PAIRED = "paired";

    /** How many recent log lines we keep in the on-screen tail view. */
    private static final int LOG_TAIL_LINES = 40;
    private static final DateTimeFormatter UI_DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AdbPairingService pairing;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Deque<String> recentLines = new ArrayDeque<>(LOG_TAIL_LINES);
    private final ScarletSessionSummary summary = new ScarletSessionSummary();

    private View pairSection;
    private View dashboardSection;
    private TextView body;
    private TextView dashboardIntro;
    private TextView status;
    private TextView batteryStatus;
    private TextView accountSummary;
    private TextView locationSummary;
    private TextView playerCountSummary;
    private TextView playerListEmpty;
    private LinearLayout playerListContainer;
    private EditText pairCodeInput;
    private EditText pairPortInput;
    private Button pairButton;
    private Button connectButton;
    private Button batteryButton;
    private Button resetButton;

    private boolean serviceStarted = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String text = intent.getStringExtra(ScarletLogService.EXTRA_TEXT);
            if (text == null) return;
            switch (intent.getAction()) {
                case ScarletLogService.ACTION_STATUS:
                    status.setText(text);
                    break;
                case ScarletLogService.ACTION_LOG_LINE:
                    appendLine(text);
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.pairSection     = findViewById(R.id.pairSection);
        this.dashboardSection= findViewById(R.id.dashboardSection);
        this.body            = (TextView) findViewById(R.id.body);
        this.dashboardIntro  = (TextView) findViewById(R.id.dashboardIntro);
        this.status          = (TextView) findViewById(R.id.status);
        this.batteryStatus   = (TextView) findViewById(R.id.batteryStatus);
        this.accountSummary  = (TextView) findViewById(R.id.accountSummary);
        this.locationSummary = (TextView) findViewById(R.id.locationSummary);
        this.playerCountSummary = (TextView) findViewById(R.id.playerCountSummary);
        this.playerListEmpty = (TextView) findViewById(R.id.playerListEmpty);
        this.playerListContainer = (LinearLayout) findViewById(R.id.playerListContainer);
        this.pairCodeInput   = (EditText) findViewById(R.id.pairCodeInput);
        this.pairPortInput   = (EditText) findViewById(R.id.pairPortInput);
        this.pairButton      = (Button)   findViewById(R.id.pairButton);
        this.connectButton   = (Button)   findViewById(R.id.connectButton);
        this.batteryButton   = (Button)   findViewById(R.id.batteryButton);
        this.resetButton     = (Button)   findViewById(R.id.resetButton);

        this.pairing = new AdbPairingService(this);

        this.pairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPairClicked(); }
        });
        this.connectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        this.batteryButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestBatteryOptimizationExemption(); }
        });
        this.resetButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onResetClicked(); }
        });

        requestNotificationPermissionIfNeeded();
        renderInitialState();
        renderSummary();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(ScarletLogService.ACTION_STATUS);
        f.addAction(ScarletLogService.ACTION_LOG_LINE);
        if (Build.VERSION.SDK_INT >= 33) {
            // Context.RECEIVER_NOT_EXPORTED = 4 (added API 33).  Hard-coded to
            // avoid a SDK field lookup on pre-33 builds of android.jar.
            registerReceiver(this.receiver, f, /*RECEIVER_NOT_EXPORTED*/ 4);
        } else {
            registerReceiver(this.receiver, f);
        }
        refreshBatteryOptimizationUi();
        reloadLatestLogSnapshot();
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(this.receiver); } catch (IllegalArgumentException ignored) {}
        super.onPause();
    }

    /** Switch the visible UI based on whether we've ever successfully paired. */
    private void renderInitialState() {
        boolean paired = prefs().getBoolean(KEY_PAIRED, false) && this.pairing.keys().hasKeys();
        if (paired) {
            this.pairSection.setVisibility(View.GONE);
            this.dashboardSection.setVisibility(View.VISIBLE);
            this.dashboardIntro.setText(R.string.connect_prompt_body);
            this.connectButton.setVisibility(View.VISIBLE);
            this.resetButton.setVisibility(View.VISIBLE);
            this.status.setText(R.string.status_ready_connect);
        } else {
            this.pairSection.setVisibility(View.VISIBLE);
            this.dashboardSection.setVisibility(View.GONE);
            this.body.setText(R.string.pair_prompt_body);
            this.status.setText("");
        }
        refreshBatteryOptimizationUi();
        renderSummary();
    }

    private void onPairClicked() {
        final String code = this.pairCodeInput.getText() == null ? "" : this.pairCodeInput.getText().toString().trim();
        if (TextUtils.isEmpty(code) || code.length() < 6) {
            this.status.setText(R.string.status_pair_code_required);
            return;
        }
        // Optional port override.  Accepts any of:
        //   "38915"                       (just the port)
        //   "192.168.1.42:38915"          (the full IP:Port shown on the
        //                                  phone's pair dialog)
        //   "127.0.0.1:38915"             (loopback form, also valid)
        // We only care about the port; the IP we always rewrite to
        // 127.0.0.1 because adbd binds to all interfaces incl. loopback.
        // Strip any non-digit run after the LAST colon (or the whole input
        // if there's no colon), parse what's left as an int.  Empty or
        // unparseable -> 0 -> mDNS discovery.
        int portOverride = 0;
        String portRaw = this.pairPortInput.getText() == null ? "" : this.pairPortInput.getText().toString().trim();
        if (!TextUtils.isEmpty(portRaw)) {
            int colon = portRaw.lastIndexOf(':');
            String tail = colon >= 0 ? portRaw.substring(colon + 1) : portRaw;
            // Drop any trailing whitespace / stray characters
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (c >= '0' && c <= '9') digits.append(c);
                else if (digits.length() > 0) break;
            }
            if (digits.length() > 0) {
                try {
                    int p = Integer.parseInt(digits.toString());
                    if (p > 0 && p < 65536) portOverride = p;
                } catch (NumberFormatException ignored) {}
            }
        }
        this.pairButton.setEnabled(false);
        this.pairCodeInput.setEnabled(false);
        this.pairPortInput.setEnabled(false);
        this.status.setText(R.string.status_pairing);
        this.pairing.pair(code, portOverride, new AdbPairingService.Callback() {
            @Override public void onStatus(final String s) {
                ui.post(new Runnable() { @Override public void run() { status.setText(s); } });
            }
            @Override public void onPaired() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        prefs().edit().putBoolean(KEY_PAIRED, true).apply();
                        // Drop the pair-code field and switch to the connect view.
                        renderInitialState();
                        status.setText(R.string.status_paired_now_connect);
                        // Auto-kick a connect attempt - user just successfully
                        // paired and almost certainly wants to start tailing.
                        onConnectClicked();
                    }
                });
            }
            @Override public void onConnected() { /* unused on the pair path */ }
            @Override public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        pairButton.setEnabled(true);
                        pairCodeInput.setEnabled(true);
                        pairPortInput.setEnabled(true);
                        status.setText(getString(R.string.status_error, message));
                    }
                });
            }
        });
    }

    private void onConnectClicked() {
        if (!isIgnoringBatteryOptimizations()) {
            this.status.setText(R.string.status_battery_optimization_required);
            requestBatteryOptimizationExemption();
            return;
        }
        this.connectButton.setEnabled(false);
        this.status.setText(R.string.status_connecting);
        this.pairing.connect(new AdbPairingService.Callback() {
            @Override public void onStatus(final String s) {
                ui.post(new Runnable() { @Override public void run() { status.setText(s); } });
            }
            @Override public void onPaired() { /* unused on the connect path */ }
            @Override public void onConnected() {
                ui.post(new Runnable() {
                    @Override public void run() {
                        connectButton.setEnabled(true);
                        connectButton.setVisibility(View.GONE);
                        status.setText(R.string.status_connected);
                        startServiceOnce();
                    }
                });
            }
            @Override public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        connectButton.setEnabled(true);
                        status.setText(getString(R.string.status_error, message));
                    }
                });
            }
        });
    }

    /**
     * Forget the on-disk keypair and the "paired" flag, then bring the
     * pair UI back.  Used when the user has revoked the cert on the
     * phone (Wireless Debugging > forget device) and needs to re-pair.
     */
    private void onResetClicked() {
        ScarletLogService.stop(this);
        this.serviceStarted = false;
        this.pairing.keys().reset();
        prefs().edit().remove(KEY_PAIRED).apply();
        this.recentLines.clear();
        this.summary.clear();
        renderInitialState();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 0x01);
            }
        } catch (Exception ignored) {}
    }

    private void refreshBatteryOptimizationUi() {
        boolean ignoring = isIgnoringBatteryOptimizations();
        this.batteryStatus.setText(ignoring
            ? getString(R.string.battery_optimization_granted)
            : getString(R.string.battery_optimization_needed));
        this.batteryButton.setVisibility(ignoring ? View.GONE : View.VISIBLE);
        if (this.dashboardSection.getVisibility() == View.VISIBLE) {
            this.connectButton.setEnabled(ignoring);
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + getPackageName()));
            startActivity(request);
            this.status.setText(R.string.status_battery_optimization_prompted);
            return;
        } catch (Exception ignored) {}

        try {
            Intent settings = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(settings);
            this.status.setText(R.string.status_battery_optimization_settings);
        } catch (Exception e) {
            this.status.setText(getString(R.string.status_error, e.getMessage() == null
                ? getString(R.string.battery_optimization_settings_unavailable)
                : e.getMessage()));
        }
    }

    private void startServiceOnce() {
        if (this.serviceStarted) return;
        this.serviceStarted = true;
        ScarletLogService.start(this);
    }

    private void appendLine(String line) {
        this.recentLines.addLast(line);
        while (this.recentLines.size() > LOG_TAIL_LINES) this.recentLines.removeFirst();
        this.summary.parseLine(line);
        renderSummary();
    }

    private void reloadLatestLogSnapshot() {
        if (this.dashboardSection.getVisibility() != View.VISIBLE) return;
        File latest = findLatestMirrorLog();
        if (latest == null || !latest.isFile()) return;

        this.recentLines.clear();
        this.summary.clear();

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(latest));
            String line;
            while ((line = reader.readLine()) != null) {
                this.recentLines.addLast(line);
                while (this.recentLines.size() > LOG_TAIL_LINES) this.recentLines.removeFirst();
                this.summary.parseLine(line);
            }
        } catch (IOException ignored) {
            return;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }

        renderSummary();
    }

    private File findLatestMirrorLog() {
        File dir = ScarletLogService.resolveVrchatDir(this);
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return null;

        File best = null;
        long bestModified = Long.MIN_VALUE;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name == null || !name.startsWith("output_log")) continue;
            long modified = file.lastModified();
            if (best == null || modified > bestModified) {
                best = file;
                bestModified = modified;
            }
        }
        return best;
    }

    private void renderSummary() {
        String accountName = this.summary.authenticatedName();
        String accountId = this.summary.authenticatedId();
        if (TextUtils.isEmpty(accountName)) {
            this.accountSummary.setText(R.string.summary_account_waiting);
        } else if (TextUtils.isEmpty(accountId)) {
            this.accountSummary.setText(getString(R.string.summary_account_value, accountName));
        } else {
            this.accountSummary.setText(getString(R.string.summary_account_value_with_id, accountName, accountId));
        }

        String location = this.summary.location();
        this.locationSummary.setText(TextUtils.isEmpty(location)
            ? getString(R.string.summary_location_waiting)
            : getString(R.string.summary_location_value, location));

        this.playerCountSummary.setText(getString(
            R.string.summary_players_value, this.summary.activePlayerCount()));

        this.playerListContainer.removeAllViews();
        boolean hasPlayers = false;
        for (ScarletSessionSummary.PlayerEntry player : this.summary.players()) {
            hasPlayers = true;
            this.playerListContainer.addView(createPlayerCard(player));
        }
        this.playerListEmpty.setVisibility(hasPlayers ? View.GONE : View.VISIBLE);
    }

    private View createPlayerCard(ScarletSessionSummary.PlayerEntry player) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        card.setBackgroundResource(R.drawable.bg_card_alt);

        TextView name = new TextView(this);
        name.setTextColor(getResources().getColor(R.color.scarlet_text));
        name.setTextSize(18f);
        name.setText(TextUtils.isEmpty(player.name)
            ? getString(R.string.player_card_name_fallback)
            : player.name);
        card.addView(name);

        card.addView(makeMetaLine(getString(R.string.player_card_id,
            TextUtils.isEmpty(player.id) ? "unknown" : player.id)));
        card.addView(makeMetaLine(TextUtils.isEmpty(player.avatarName)
            ? getString(R.string.player_card_avatar_unknown)
            : getString(R.string.player_card_avatar, player.avatarName)));
        card.addView(makeMetaLine(TextUtils.isEmpty(player.ageVerificationStatus)
            ? getString(R.string.player_card_agever_unknown)
            : getString(R.string.player_card_agever, player.ageVerificationStatus)));
        card.addView(makeMetaLine(TextUtils.isEmpty(player.pronouns)
            ? getString(R.string.player_card_pronouns_unknown)
            : getString(R.string.player_card_pronouns, player.pronouns)));
        card.addView(makeMetaLine(getString(R.string.player_card_joined,
            player.joined == null ? "waiting" : player.joined.format(UI_DTF))));
        card.addView(makeMetaLine(player.left == null
            ? getString(R.string.player_card_still_here)
            : getString(R.string.player_card_left, player.left.format(UI_DTF))));

        return card;
    }

    private TextView makeMetaLine(String text) {
        TextView line = new TextView(this);
        line.setText(text);
        line.setTextColor(getResources().getColor(R.color.scarlet_text_muted));
        line.setTextSize(14f);
        line.setPadding(0, dp(6), 0, 0);
        return line;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
