package net.sybyline.scarlet.android;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.net.InetAddress;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Owns the libadb-android connection manager and drives the
 * Wireless-Debugging pairing handshake plus the post-pair TLS connect.
 *
 * <p>Why this layer exists at all: libadb-android exposes
 * {@link AbsAdbConnectionManager} as an abstract type that has to be
 * subclassed in the consuming app to plug in the keystore (private key,
 * X.509 certificate, device name).  The subclass is the one stable
 * "owner of the ADB session" the rest of Scarlet talks to - both the
 * pair-time UI in {@link MainActivity} and the long-lived
 * {@link ScarletLogService} hand back to a {@link Manager} instance to
 * open shell streams, disconnect, etc.
 *
 * <p>Pairing flow (one-time per device):
 * <ol>
 *   <li>User enables Wireless Debugging on the phone, taps
 *       <em>Pair device with pairing code</em>.</li>
 *   <li>Phone shows a 6-digit code and announces a pairing port via mDNS
 *       under {@code _adb-tls-pairing._tcp}.</li>
 *   <li>Scarlet runs {@link #pair(String, Callback)} which scans NSD for
 *       that service type, takes the first responding port, and calls
 *       {@code manager.pair(port, code)} - SPAKE2 handshake, key
 *       exchange.</li>
 *   <li>On success the phone persists Scarlet's cert in adbd's
 *       {@code adb_pairing_keys}; subsequent launches connect silently
 *       via {@link #connect(Callback)}.</li>
 * </ol>
 *
 * <p>Connect flow (every launch after the first):
 * <ol>
 *   <li>{@link #connect(Callback)} delegates to
 *       {@link AbsAdbConnectionManager#connectTls(Context, long)} which
 *       does its own mDNS discovery on {@code _adb-tls-connect._tcp} and
 *       opens a TLS session.</li>
 * </ol>
 */
final class AdbPairingService {

    private static final String TAG = "Scarlet/Pairing";

    /** Time we'll wait for an mDNS pairing-port response. */
    private static final long PAIR_DISCOVER_TIMEOUT_MS = 30_000L;

    /** Time we'll wait for an mDNS connect-port response. */
    private static final long CONNECT_TIMEOUT_MS = 30_000L;

    interface Callback {
        void onStatus(String s);
        void onPaired();
        void onConnected();
        void onError(String message);
    }

    private final Context ctx;
    private final AdbKeyStore keys;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Manager manager;

    AdbPairingService(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.keys = new AdbKeyStore(this.ctx);
    }

    AdbKeyStore keys() { return this.keys; }

    /**
     * Get (lazy-creating) the libadb-android connection manager.  The same
     * instance is reused across pairing and connect attempts so adbd sees
     * a stable cert and the on-device "Always allow" memory keeps
     * working.
     */
    synchronized Manager manager() throws Exception {
        Manager m = this.manager;
        if (m != null) return m;
        this.keys.ensureKeys(deviceName());
        m = new Manager(this.keys);
        m.setApi(Build.VERSION.SDK_INT);
        this.manager = m;
        return m;
    }

    /**
     * Run the SPAKE2 pairing handshake against the loopback adbd.  Caller
     * supplies the 6-digit code shown on the phone's Wireless Debugging
     * pairing screen.
     */
    void pair(final String code, final Callback cb) {
        pair(code, 0, cb);
    }

    /**
     * Pair with an explicit port (overrides mDNS discovery).  Use this when
     * the user can read the port from the on-device pair dialog directly
     * (it always prints "IP address & Port: 127.0.0.1:NNNN" alongside the
     * 6-digit code) - skips the {@code _adb-tls-pairing._tcp} NSD scan,
     * which is unreliable on certain OEM Wi-Fi stacks.
     *
     * @param code     6-digit pairing code from the phone
     * @param portHint pairing port the dialog displays.  Pass {@code 0} to
     *                 fall back to mDNS discovery.
     */
    void pair(final String code, final int portHint, final Callback cb) {
        if (this.busy.getAndSet(true)) {
            cb.onError("already busy");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    cb.onStatus("Loading keypair...");
                    Manager m = manager();

                    int port;
                    if (portHint > 0) {
                        port = portHint;
                        cb.onStatus("Pairing on 127.0.0.1:" + port + "...");
                    } else {
                        cb.onStatus("Looking for pairing service on this device...");
                        Endpoint ep = discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING, PAIR_DISCOVER_TIMEOUT_MS);
                        if (ep == null) {
                            cb.onError("No pairing service advertised. Either keep the "
                                     + "Wireless debugging > Pair device with pairing code "
                                     + "dialog visible while pairing, or type the port from "
                                     + "that dialog into the optional port field.");
                            return;
                        }
                        port = ep.port;
                        cb.onStatus("Pairing on " + ep + "...");
                    }

                    boolean ok = m.pair(port, code);
                    if (!ok) {
                        cb.onError("Pairing rejected. Check the 6-digit code and try again.");
                        return;
                    }

                    cb.onPaired();
                } catch (Throwable t) {
                    Log.w(TAG, "pair() failed", t);
                    cb.onError(humanize(t));
                } finally {
                    busy.set(false);
                }
            }
        }, "Scarlet-Pair");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Open a fresh TLS session against adbd, using credentials persisted
     * by an earlier {@link #pair(String, Callback)} success.  Discovery
     * is delegated to libadb-android's {@code connectTls} which does its
     * own NSD scan on {@code _adb-tls-connect._tcp}.
     */
    void connect(final Callback cb) {
        if (this.busy.getAndSet(true)) {
            cb.onError("already busy");
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    cb.onStatus("Loading keypair...");
                    Manager m = manager();
                    if (m.isConnected()) {
                        cb.onConnected();
                        return;
                    }
                    cb.onStatus("Connecting via Wireless Debugging...");
                    boolean ok = m.connectTls(ctx, CONNECT_TIMEOUT_MS);
                    if (!ok) {
                        cb.onError("No Wireless Debugging service advertised. "
                                 + "Open Settings > Developer options > Wireless debugging.");
                        return;
                    }
                    cb.onConnected();
                } catch (Throwable t) {
                    Log.w(TAG, "connect() failed", t);
                    cb.onError(humanize(t));
                } finally {
                    busy.set(false);
                }
            }
        }, "Scarlet-Connect");
        worker.setDaemon(true);
        worker.start();
    }

    /** Best-effort device-name hint shown in the pairing UI on adbd. */
    private static String deviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String manuf = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        if (model.isEmpty() && manuf.isEmpty()) return "Scarlet";
        if (manuf.isEmpty() || model.toLowerCase().startsWith(manuf.toLowerCase())) return "Scarlet on " + model;
        return "Scarlet on " + manuf + " " + model;
    }

    private static String humanize(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }

    /**
     * Tiny synchronous wrapper around AdbMdns: starts a scan, waits for
     * the first valid port event, returns it, and stops the scan.  Used
     * for the pairing-port lookup; connect-time discovery is handled
     * inside libadb-android's connectTls instead.
     *
     * <p>Holds a {@link WifiManager.MulticastLock} for the duration of
     * the scan.  NsdManager itself acquires a system-side multicast lock,
     * but several OEM Wi-Fi stacks (notably Samsung One UI on Android
     * 13+) silently drop multicast packets destined for unprivileged apps
     * unless the app holds its own lock too.  Without this, the
     * {@code _adb-tls-pairing._tcp.local} announcements that the on-
     * device adbd emits never reach the listener and the scan times out
     * even with the pair dialog visible.  Acquiring it costs near-zero
     * battery for the few seconds we're scanning; we release it the
     * moment a port arrives or the timeout expires.
     */
    private Endpoint discover(String serviceType, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Endpoint> ref = new AtomicReference<>();
        final AdbMdns mdns = new AdbMdns(this.ctx, serviceType, new AdbMdns.OnAdbDaemonDiscoveredListener() {
            @Override public void onPortChanged(InetAddress host, int port) {
                if (port > 0 && ref.compareAndSet(null, new Endpoint(host, port))) {
                    latch.countDown();
                }
            }
        });
        WifiManager.MulticastLock lock = null;
        try {
            WifiManager wifi = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                lock = wifi.createMulticastLock("Scarlet-AdbMdns");
                lock.setReferenceCounted(false);
                try { lock.acquire(); } catch (Throwable t) { Log.w(TAG, "multicast lock acquire failed", t); }
            }
            mdns.start();
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            return ref.get();
        } finally {
            try { mdns.stop(); } catch (Throwable ignored) {}
            if (lock != null) {
                try { lock.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static final class Endpoint {
        final InetAddress host;
        final int port;
        Endpoint(InetAddress host, int port) { this.host = host; this.port = port; }
        @Override public String toString() {
            return (host == null ? "?" : host.getHostAddress()) + ":" + port;
        }
    }

    /**
     * Concrete {@link AbsAdbConnectionManager} that hands libadb-android
     * the on-disk keypair + cert and a friendly device name.  Cached on
     * the enclosing service so the same manager is reused across the
     * pair UI and the long-running log-tail service.
     */
    static final class Manager extends AbsAdbConnectionManager {
        private final AdbKeyStore keys;
        private final String name;
        private volatile PrivateKey priv;
        private volatile Certificate cert;

        Manager(AdbKeyStore keys) {
            this.keys = keys;
            this.name = AdbPairingService.deviceName();
        }

        @Override
        protected PrivateKey getPrivateKey() {
            try {
                PrivateKey p = this.priv;
                if (p == null) this.priv = p = this.keys.loadPrivateKey();
                return p;
            } catch (Exception e) {
                throw new RuntimeException("could not load ADB private key", e);
            }
        }

        @Override
        protected Certificate getCertificate() {
            try {
                Certificate c = this.cert;
                if (c == null) this.cert = c = this.keys.loadCertificate();
                return c;
            } catch (Exception e) {
                throw new RuntimeException("could not load ADB certificate", e);
            }
        }

        @Override
        protected String getDeviceName() {
            return this.name;
        }
    }
}
