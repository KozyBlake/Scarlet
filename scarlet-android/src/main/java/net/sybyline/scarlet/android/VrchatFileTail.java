package net.sybyline.scarlet.android;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AdbStream;

/**
 * Tails VRChat's own {@code output_log_*.txt} file from inside its app
 * sandbox over an ADB shell, and mirrors each line into Scarlet's local
 * capture directory.
 *
 * <p><b>Why this instead of logcat:</b> the VRChat Android app is a Unity
 * release build, which strips {@code Debug.Log} output from logcat.  A
 * logcat-based tail therefore sees nothing meaningful.  VRChat does,
 * however, write a real {@code output_log_*.txt} on every session into
 * {@code /storage/emulated/0/Android/data/com.vrchat.mobile.playstore/files/},
 * and that file is already in the exact format desktop Scarlet consumes.
 *
 * <p><b>Why this doesn't need root or {@code MANAGE_EXTERNAL_STORAGE}:</b>
 * on Android 11+ a normal app cannot read another app's
 * {@code /Android/data/} directory via {@code java.io.File}, but the
 * {@code shell} user (which {@code adbd} runs commands as) can.  We
 * already have a libadb-android session (TLS over Wireless Debugging),
 * so we just issue a {@code shell:tail -F} and stream lines back.
 */
final class VrchatFileTail implements AndroidLogTail {

    private static final String TAG = "Scarlet/FileTail";

    /**
     * Candidate external-storage locations that may contain VRChat's shared
     * output logs. We keep this broad because VRChat Mobile's package / storage
     * layout has changed across releases and storefront variants.
     */
    private static final String[] DISCOVER_GLOBS = new String[] {
        "/storage/emulated/0/Android/data/com.vrchat*/files/output_log*.txt",
        "/sdcard/Android/data/com.vrchat*/files/output_log*.txt",
        "/storage/emulated/0/Android/data/com.vrchat*/files/Logs/output_log*.txt",
        "/sdcard/Android/data/com.vrchat*/files/Logs/output_log*.txt"
    };

    /** How often the watcher re-runs discovery to catch session rotation. */
    private static final long REDISCOVER_MS = 15_000L;

    /** How long to wait before re-polling when VRChat hasn't produced a log yet. */
    private static final long IDLE_SLEEP_MS = 3_000L;

    interface LineSink { void onLine(String line); }

    private final AdbPairingService.Manager manager;
    private final File outputDir;
    private final LineSink uiSink;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<AdbStream> openStream = new AtomicReference<>();

    private volatile Thread worker;
    private volatile Thread watcher;
    private volatile File currentOutputFile;
    private volatile String currentSourcePath;

    VrchatFileTail(AdbPairingService.Manager manager, File outputDir, LineSink uiSink) {
        this.manager = manager;
        this.outputDir = outputDir;
        this.uiSink = uiSink;
    }

    @Override public File currentOutputFile() { return this.currentOutputFile; }
    @Override public boolean isRunning() { return this.running.get(); }
    String currentSourcePath() { return this.currentSourcePath; }

    @Override
    public synchronized void start() {
        if (this.running.getAndSet(true)) return;
        this.worker = new Thread(new Runnable() {
            @Override public void run() { runLoop(); }
        }, "Scarlet-FileTail");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public synchronized void stop() {
        this.running.set(false);
        closeCurrentStream();
        Thread w = this.worker;
        if (w != null) w.interrupt();
        Thread wa = this.watcher;
        if (wa != null) wa.interrupt();
    }

    /**
     * One-shot discovery of the newest output_log file VRChat has written.
     * Returns {@code null} if VRChat hasn't produced one yet.
     */
    String discoverLatest() {
        try {
            String out = runShell(buildDiscoverCommand());
            if (out == null || out.isEmpty()) return null;
            // ls -1t puts the newest name first; take that single line.
            int nl = out.indexOf('\n');
            String first = (nl >= 0 ? out.substring(0, nl) : out).trim();
            return first.isEmpty() ? null : first;
        } catch (Throwable t) {
            Log.w(TAG, "discoverLatest failed", t);
            return null;
        }
    }

    /**
     * Run a shell command, slurp stdout, return as a UTF-8 string.  Used
     * for short one-shot lookups (ls).  Long-running tails go through
     * {@link #tailOne(String)} instead so we keep a handle to the
     * underlying stream and can close it on rotation.
     */
    private String runShell(String cmd) throws IOException, InterruptedException {
        AdbStream s = this.manager.openStream("shell:" + cmd);
        try {
            InputStream in = s.openInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            byte[] buf = new byte[1024];
            try {
                int r;
                while ((r = in.read(buf)) >= 0) baos.write(buf, 0, r);
            } catch (IOException ioe) {
                // libadb-android may signal normal shell completion as
                // "Stream closed" instead of returning EOF. Preserve any
                // output we already collected and treat that as success.
                String msg = ioe.getMessage();
                if (msg == null || !msg.contains("Stream closed")) throw ioe;
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private void runLoop() {
        if (!this.outputDir.isDirectory() && !this.outputDir.mkdirs()) {
            Log.e(TAG, "Could not create output dir " + this.outputDir);
            this.running.set(false);
            return;
        }

        // Background watcher: re-scans VRChat's dir periodically so we notice
        // when a new session rotates to a fresh output_log file.
        this.watcher = new Thread(new Runnable() {
            @Override public void run() { watchForRotation(); }
        }, "Scarlet-FileTail-Watch");
        this.watcher.setDaemon(true);
        this.watcher.start();

        try {
            while (this.running.get()) {
                String srcPath = discoverLatest();
                if (srcPath == null) {
                    this.currentSourcePath = null;
                    Thread.sleep(IDLE_SLEEP_MS);
                    continue;
                }
                this.currentSourcePath = srcPath;
                tailOne(srcPath);
                // tailOne returns when the stream closes (rotation / error);
                // loop rediscovers a potentially-newer file.
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            Thread w = this.watcher;
            if (w != null) w.interrupt();
            this.running.set(false);
            closeCurrentStream();
        }
    }

    private void watchForRotation() {
        try {
            while (this.running.get()) {
                Thread.sleep(REDISCOVER_MS);
                String cur = this.currentSourcePath;
                if (cur == null) continue;
                String latest = discoverLatest();
                if (latest != null && !latest.equals(cur)) {
                    Log.i(TAG, "VRChat rotated log: " + cur + " -> " + latest);
                    closeCurrentStream();
                }
            }
        } catch (InterruptedException ignored) {}
    }

    /**
     * Mirror-tail one VRChat log file.  Opens a local mirror under
     * {@link #outputDir} and streams every line of the source through
     * {@code tail -F} into it.  Returns when the underlying ADB stream
     * closes (rotation, service shutdown, or adbd drop).
     */
    private void tailOne(String srcPath) {
        File mirror = new File(this.outputDir, mirrorNameFor(srcPath));
        this.currentOutputFile = mirror;

        String cmd = "shell:tail -n +1 -F " + shellQuote(srcPath);
        AdbStream stream = null;
        try {
            stream = this.manager.openStream(cmd);
            this.openStream.set(stream);

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream.openInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter out = Files.newBufferedWriter(
                    mirror.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                String line;
                while (this.running.get() && (line = br.readLine()) != null) {
                    out.write(line);
                    out.newLine();
                    out.flush();
                    LineSink sink = this.uiSink;
                    if (sink != null) sink.onLine(line);
                }
            }
        } catch (IOException ioe) {
            Log.w(TAG, "Tail on " + srcPath + " ended: " + ioe.getMessage());
            sleepQuietly(IDLE_SLEEP_MS);
        } catch (Throwable t) {
            Log.e(TAG, "Tail on " + srcPath + " crashed", t);
            sleepQuietly(IDLE_SLEEP_MS);
        } finally {
            if (stream != null) this.openStream.compareAndSet(stream, null);
            closeStreamQuietly(stream);
        }
    }

    private void closeCurrentStream() {
        AdbStream s = this.openStream.getAndSet(null);
        closeStreamQuietly(s);
    }

    private static void closeStreamQuietly(AdbStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String buildDiscoverCommand() {
        StringBuilder cmd = new StringBuilder(192);
        cmd.append("ls -1t");
        for (String glob : DISCOVER_GLOBS) {
            cmd.append(' ').append(glob);
        }
        cmd.append(" 2>/dev/null");
        return cmd.toString();
    }

    /** Quote a single shell argument safely (single-quote + escape embedded '). */
    static String shellQuote(String arg) {
        if (arg == null) return "''";
        StringBuilder sb = new StringBuilder(arg.length() + 2);
        sb.append('\'');
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\'') sb.append("'\\''");
            else sb.append(c);
        }
        sb.append('\'');
        return sb.toString();
    }

    /** Derive the local mirror filename from the remote source path. */
    static String mirrorNameFor(String srcPath) {
        int slash = srcPath.lastIndexOf('/');
        // Keep VRChat's own filename - desktop Scarlet scans for "output_log*".
        return slash >= 0 ? srcPath.substring(slash + 1) : srcPath;
    }
}
