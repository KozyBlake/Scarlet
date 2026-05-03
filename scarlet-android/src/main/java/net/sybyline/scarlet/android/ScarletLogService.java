package net.sybyline.scarlet.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that owns the libadb-android session and drives
 * {@link VrchatFileTail}.
 *
 * <p>Everything privileged happens here: we ensure a TLS session against
 * the loopback adbd is up (libadb-android handles NSD discovery on
 * {@code _adb-tls-connect._tcp} internally), then hand the resulting
 * {@link AdbPairingService.Manager} to a {@link VrchatFileTail} which
 * streams VRChat's {@code output_log_*.txt} into Scarlet's mirror
 * directory.
 *
 * <p>UI state is surfaced via local broadcasts scoped to our own package
 * (see {@code ACTION_*} constants) so {@link MainActivity} can reflect
 * progress without holding a service binding.
 */
public final class ScarletLogService extends Service {

    private static final String TAG = "Scarlet/Service";

    private static final String CHANNEL_ID = "scarlet.log";
    private static final int NOTIFICATION_ID = 0xCA_FE;

    /** Reconnect backoff when adbd drops / the locator finds nothing. */
    private static final long RECONNECT_DELAY_MS = 5_000L;
    /** TLS-connect budget per attempt (libadb's own NSD scan + handshake). */
    private static final long CONNECT_TIMEOUT_MS = 15_000L;

    /** Status text changed. Extra: {@link #EXTRA_TEXT}. */
    public static final String ACTION_STATUS      = "net.sybyline.scarlet.android.STATUS";
    /** A new log line was mirrored.  Extra: {@link #EXTRA_TEXT}. */
    public static final String ACTION_LOG_LINE    = "net.sybyline.scarlet.android.LOG_LINE";
    /** Local mirror file path changed.  Extra: {@link #EXTRA_TEXT}. */
    public static final String ACTION_OUTPUT_PATH = "net.sybyline.scarlet.android.OUTPUT_PATH";
    /** Remote source path changed.  Extra: {@link #EXTRA_TEXT}. */
    public static final String ACTION_SOURCE_PATH = "net.sybyline.scarlet.android.SOURCE_PATH";

    public static final String EXTRA_TEXT = "text";

    private final AtomicBoolean alive = new AtomicBoolean(false);
    private Thread supervisor;
    private volatile AdbPairingService pairing;
    private volatile VrchatFileTail tail;
    private volatile PowerManager.WakeLock wakeLock;

    /** Convenience for the activity: start (and foreground) the service. */
    static void start(Context ctx) {
        Intent i = new Intent(ctx, ScarletLogService.class);
        ctx.startForegroundService(i);
    }

    /** Convenience for the activity: stop the service cleanly. */
    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ScarletLogService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (this.alive.getAndSet(true)) return START_STICKY;
        this.supervisor = new Thread(new Runnable() {
            @Override public void run() { superviseLoop(); }
        }, "Scarlet-Supervisor");
        this.supervisor.setDaemon(true);
        this.supervisor.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        this.alive.set(false);
        releaseWakeLock();
        VrchatFileTail t = this.tail;
        if (t != null) t.stop();
        Thread s = this.supervisor;
        if (s != null) s.interrupt();
        AdbPairingService p = this.pairing;
        if (p != null) {
            try {
                AdbPairingService.Manager m = p.manager();
                if (m != null) m.close();
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /**
     * Resolve the directory Scarlet mirrors VRChat's log into.  Defaults to
     * {@code <filesDir>/vrchat/}; a host deployment can override via the
     * {@code scarlet.vrcAppData.dir} system property or the
     * {@code SCARLET_VRC_APPDATA_DIR} environment variable so that desktop
     * Scarlet's existing log-watcher path continues to find it.
     */
    static File resolveVrchatDir(Context ctx) {
        String override = System.getProperty("scarlet.vrcAppData.dir");
        if (override == null || override.isEmpty()) override = System.getenv("SCARLET_VRC_APPDATA_DIR");
        File dir = (override != null && !override.isEmpty())
            ? new File(override)
            : new File(ctx.getFilesDir(), "vrchat");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create " + dir + "; falling back to filesDir");
            dir = ctx.getFilesDir();
        }
        return dir;
    }

    private void superviseLoop() {
        acquireWakeLock();
        AdbPairingService p = new AdbPairingService(this);
        this.pairing = p;
        File outputDir = resolveVrchatDir(this);

        while (this.alive.get()) {
            try {
                if (!p.keys().hasKeys()) {
                    broadcast(ACTION_STATUS,
                        getString(R.string.status_error, "ADB keys missing - re-pair via the app"));
                    sleepQuietly(RECONNECT_DELAY_MS);
                    continue;
                }

                AdbPairingService.Manager m = p.manager();
                if (!m.isConnected()) {
                    broadcast(ACTION_STATUS, getString(R.string.status_connecting));
                    boolean ok = m.connectTls(this, CONNECT_TIMEOUT_MS);
                    if (!ok) {
                        broadcast(ACTION_STATUS,
                            getString(R.string.status_error,
                                "no _adb-tls-connect._tcp advertised - is Wireless Debugging on?"));
                        sleepQuietly(RECONNECT_DELAY_MS);
                        continue;
                    }
                }

                VrchatFileTail ft = new VrchatFileTail(m, outputDir, new VrchatFileTail.LineSink() {
                    @Override public void onLine(String line) {
                        broadcast(ACTION_LOG_LINE, line);
                    }
                });
                this.tail = ft;

                broadcast(ACTION_STATUS, getString(R.string.status_paired));
                ft.start();

                // Poll for state transitions: emit path broadcasts whenever the
                // tail rotates to a new source, and unwind this iteration when
                // the service stops or the tail loop exits on its own.
                String lastSrc = null;
                File lastMirror = null;
                while (this.alive.get() && ft.isRunning()) {
                    String src = ft.currentSourcePath();
                    if (src != null && !src.equals(lastSrc)) {
                        lastSrc = src;
                        broadcast(ACTION_SOURCE_PATH, src);
                        broadcast(ACTION_STATUS, getString(R.string.status_running));
                    }
                    File mf = ft.currentOutputFile();
                    if (mf != null && !mf.equals(lastMirror)) {
                        lastMirror = mf;
                        broadcast(ACTION_OUTPUT_PATH, mf.getAbsolutePath());
                    }
                    Thread.sleep(1_000L);
                }

                ft.stop();
                try { m.disconnect(); } catch (Throwable ignored) {}
                this.tail = null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                Log.w(TAG, "Supervisor iteration failed", t);
                String msg = t.getMessage();
                broadcast(ACTION_STATUS, getString(R.string.status_error,
                    msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg));
                sleepQuietly(RECONNECT_DELAY_MS);
            }
        }

        VrchatFileTail t = this.tail;
        if (t != null) t.stop();
        try {
            AdbPairingService.Manager m = p.manager();
            if (m != null) m.close();
        } catch (Throwable ignored) {}
        releaseWakeLock();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notification_channel_description));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private void broadcast(String action, String text) {
        Intent i = new Intent(action).setPackage(getPackageName());
        i.putExtra(EXTRA_TEXT, text);
        sendBroadcast(i);

        // Keep the foreground notification mirroring the most recent status.
        if (ACTION_STATUS.equals(action)) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private void acquireWakeLock() {
        if (this.wakeLock != null && this.wakeLock.isHeld()) return;
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":log-supervisor");
            wl.setReferenceCounted(false);
            wl.acquire();
            this.wakeLock = wl;
        } catch (Throwable t) {
            Log.w(TAG, "Could not acquire PARTIAL_WAKE_LOCK", t);
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock wl = this.wakeLock;
        this.wakeLock = null;
        if (wl == null) return;
        try {
            if (wl.isHeld()) wl.release();
        } catch (Throwable ignored) {}
    }
}
