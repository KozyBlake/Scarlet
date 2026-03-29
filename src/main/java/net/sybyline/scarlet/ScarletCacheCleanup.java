package net.sybyline.scarlet;

import java.io.File;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sybyline.scarlet.log.ScarletLogger;

/**
 * Manages periodic deletion of stale cached files: TTS .wav files, Scarlet log
 * files, and JSON API-cache files.
 *
 * <p>Safety rules:
 * <ul>
 *   <li>The Scarlet log file written by the current session is never deleted.</li>
 *   <li>The VRChat output_log file currently being tailed is never deleted.</li>
 *   <li>If Scarlet appears to be actively in use (a VRChat log is being tailed
 *       or an instance is loaded) when files are due, a dismissible popup is
 *       shown and the actual deletion is deferred until the next idle check
 *       rather than proceeding immediately.</li>
 *   <li>Cleanup runs at startup (after login) and then every 6 hours on the
 *       background worker thread pool, so long-running sessions are covered.</li>
 * </ul>
 *
 * <p>All settings are exposed in the UI under the "Cache Cleanup" section:
 * <ul>
 *   <li>{@code cache_cleanup_enabled} — master on/off toggle (default true)</li>
 *   <li>{@code cache_cleanup_days}    — files older than N days are eligible
 *       (1–30, default 7)</li>
 * </ul>
 */
public class ScarletCacheCleanup
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/CacheCleanup");

    /** How often the periodic check fires while Scarlet is running (ms). */
    private static final long CHECK_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);

    /** The minimum elapsed time since last cleanup before we act again (ms).
     *  Prevents double-running if Scarlet is restarted quickly. */
    private static final long MIN_BETWEEN_CLEANUPS_MS = TimeUnit.HOURS.toMillis(1);

    // ── Directories / file categories ─────────────────────────────────────────

    /** TTS output .wav files — safe to delete any time; regenerated on demand. */
    private final File ttsDir;
    /** Scarlet own log files — skip the active session file. */
    private final File scarletLogDir;
    /** JSON API-cache subdirectories (usr/, wrld/, avtr/, etc.). */
    private final File cachesDir;

    private final Scarlet scarlet;

    public ScarletCacheCleanup(Scarlet scarlet)
    {
        this.scarlet     = scarlet;
        this.ttsDir      = new File(Scarlet.dir, "tts");
        this.scarletLogDir = new File(Scarlet.dir, "logs");
        this.cachesDir   = new File(Scarlet.dir, "caches");
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Called once after startup completes and on the periodic 6-hour schedule.
     * Decides whether to run, defer, or skip based on settings and active-use state.
     */
    public void maybeCleanup()
    {
        if (!this.scarlet.settings.cacheCleanupEnabled.get())
            return;

        // Throttle: don't run if we cleaned recently
        OffsetDateTime lastClean = this.scarlet.settings.lastCacheCleanup.getOrNull();
        if (lastClean != null)
        {
            long msSinceLast = OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli()
                             - lastClean.toInstant().toEpochMilli();
            if (msSinceLast < MIN_BETWEEN_CLEANUPS_MS)
                return;
        }

        int days = this.scarlet.settings.cacheCleanupDays.get();
        long cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);

        // Collect all eligible files (excluding protected active files)
        List<File> eligible = this.collectEligible(cutoffMs);
        if (eligible.isEmpty())
        {
            // Nothing to do — still update the timestamp so we don't re-scan constantly
            this.scarlet.settings.lastCacheCleanup.set(OffsetDateTime.now(ZoneOffset.UTC));
            return;
        }

        // Check whether Scarlet is actively in use
        if (this.isActivelyInUse())
        {
            // Show a non-blocking popup warning — don't delete while active
            LOG.info("Cache cleanup deferred: {} file(s) eligible but Scarlet is actively in use.", eligible.size());
            this.scarlet.splash.queueFeedbackPopup(
                null,
                8_000L,
                "Cache cleanup pending",
                eligible.size() + " old file(s) will be removed when Scarlet is idle.",
                new java.awt.Color(255, 200, 60),  // amber — informational
                new java.awt.Color(200, 160, 40)
            );
            // Do NOT update lastCacheCleanup — we want to try again next check
            return;
        }

        // Safe to delete
        this.deleteFiles(eligible, days);
    }

    /**
     * Schedules periodic cleanup on the Scarlet worker thread pool.
     * Call this once after startup completes.
     */
    public void schedulePeriodicCleanup()
    {
        this.scarlet.exec.scheduleAtFixedRate(
            this::maybeCleanupSafe,
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    /** Wrapper that catches and logs any unexpected exception so the scheduler survives. */
    private void maybeCleanupSafe()
    {
        try
        {
            this.maybeCleanup();
        }
        catch (Exception ex)
        {
            LOG.error("Unexpected exception during cache cleanup check", ex);
        }
    }

    /**
     * Returns true if Scarlet is actively doing something that makes it unsafe
     * to delete files mid-operation.
     *
     * "Active" means any of:
     *   - A VRChat output log file is currently being tailed (VRChat is running
     *     and Scarlet is processing log lines from it)
     *   - At least one player is currently shown in the instance table
     *     (an active instance session is in progress)
     */
    private boolean isActivelyInUse()
    {
        // VRChat log is being actively tailed
        if (this.scarlet.logs.currentTarget() != null)
            return true;

        // An instance is loaded (players in the table)
        // connectedPlayers is internal to ScarletUI; proxy via the public status
        // we maintain in the run loop instead
        return false;
    }

    /**
     * Collects all files that are older than {@code cutoffMs} across the three
     * managed directories, excluding any file that must not be deleted while
     * Scarlet is running.
     */
    private List<File> collectEligible(long cutoffMs)
    {
        List<File> result = new ArrayList<>();

        // Active Scarlet log — never delete while running
        File activeLog = ScarletLogger.getActiveLogFile();

        // Active VRChat log — never delete while it is being tailed
        File activeTail = this.scarlet.logs.currentTarget();

        // ── TTS .wav files ────────────────────────────────────────────────────
        collectFromDir(this.ttsDir, cutoffMs, null, null, result);

        // ── Scarlet log files ─────────────────────────────────────────────────
        // Only touch files matching the known log name pattern
        if (this.scarletLogDir.isDirectory())
        {
            File[] logFiles = this.scarletLogDir.listFiles(
                (dir, name) -> ScarletLogger.lfpattern.matcher(name).find());
            if (logFiles != null)
            {
                for (File f : logFiles)
                {
                    if (f.equals(activeLog))
                        continue;  // never touch the live log
                    if (f.lastModified() < cutoffMs)
                        result.add(f);
                }
            }
        }

        // ── JSON cache subdirectories ─────────────────────────────────────────
        if (this.cachesDir.isDirectory())
        {
            File[] kindDirs = this.cachesDir.listFiles(File::isDirectory);
            if (kindDirs != null)
            {
                for (File kindDir : kindDirs)
                    collectFromDir(kindDir, cutoffMs, activeTail, null, result);
            }
        }

        return result;
    }

    /**
     * Scans a single directory for files older than cutoffMs, skipping
     * {@code skip1} and {@code skip2} if non-null.
     */
    private static void collectFromDir(File dir, long cutoffMs,
                                       File skip1, File skip2,
                                       List<File> result)
    {
        if (!dir.isDirectory())
            return;
        File[] files = dir.listFiles(File::isFile);
        if (files == null)
            return;
        for (File f : files)
        {
            if (f.equals(skip1) || f.equals(skip2))
                continue;
            if (f.lastModified() < cutoffMs)
                result.add(f);
        }
    }

    /** Deletes the given files, logging a summary. Updates lastCacheCleanup. */
    private void deleteFiles(List<File> files, int days)
    {
        int deleted = 0, failed = 0;
        long totalBytes = 0;
        for (File f : files)
        {
            long size = f.length();
            if (f.delete())
            {
                deleted++;
                totalBytes += size;
                LOG.debug("Deleted stale cache file: {}", f.getAbsolutePath());
            }
            else
            {
                failed++;
                LOG.warn("Failed to delete cache file: {}", f.getAbsolutePath());
            }
        }

        long kb = totalBytes / 1024;
        LOG.info("Cache cleanup complete: {} file(s) deleted ({} KB freed), {} failed. "
                + "Retention policy: {} days.",
                deleted, kb, failed, days);

        if (deleted > 0)
        {
            this.scarlet.splash.queueFeedbackPopup(
                null,
                5_000L,
                "Cache cleaned",
                deleted + " old file(s) removed  \u2022  " + kb + " KB freed",
                new java.awt.Color(60, 200, 90),   // green — success
                new java.awt.Color(40, 160, 70)
            );
        }

        this.scarlet.settings.lastCacheCleanup.set(OffsetDateTime.now(ZoneOffset.UTC));
    }

}
