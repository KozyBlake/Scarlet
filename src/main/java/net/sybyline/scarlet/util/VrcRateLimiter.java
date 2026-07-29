package net.sybyline.scarlet.util;

/**
 * A single global gate for all VRChat API traffic, complementing (not replacing)
 * the per-feature pacers elsewhere in Scarlet.
 *
 * <p>The per-feature limiters stop any one feature from spamming a specific
 * operation; this limiter bounds the <em>aggregate</em> request rate across every
 * feature at once — the situation that actually trips VRChat's limits (e.g. joining
 * a busy instance fires audit polling, avatar hydration for dozens of players, and
 * user/group lookups simultaneously). It does two things:
 *
 * <ol>
 *   <li><b>Burst smoothing</b> — a token bucket caps the sustained request rate
 *       while allowing short bursts.</li>
 *   <li><b>Global backoff</b> — when VRChat answers 429 (or the caller reports a
 *       rate-limit), <em>all</em> subsequent calls wait out the backoff, honoring a
 *       {@code Retry-After} hint when present. So one 429 eases the whole client off
 *       rather than only the feature that received it.</li>
 * </ol>
 *
 * <p>Intended to sit in the VRChat okhttp client's interceptor chain, so every call
 * — generated API methods and hand-built ones alike — passes through it with no
 * change to call sites. Thread-safe; waits happen outside the lock.
 */
public final class VrcRateLimiter
{
    private final double maxPermits;
    private final double refillPerMs;
    private double available;
    private long lastRefillMs;

    private volatile long backoffUntilMs = 0L;
    private volatile long lastPenaltyMs = 0L;

    /** Longest single sleep chunk, so backoff changes are noticed promptly. */
    private static final long MAX_SLEEP_CHUNK_MS = 1_000L;

    /**
     * @param permitsPerSecond sustained request ceiling
     * @param burst            bucket capacity (how many can go back-to-back)
     */
    public VrcRateLimiter(double permitsPerSecond, double burst)
    {
        this.refillPerMs = Math.max(0.0001, permitsPerSecond) / 1000.0;
        this.maxPermits = Math.max(1.0, burst);
        this.available = this.maxPermits;
        this.lastRefillMs = System.currentTimeMillis();
    }

    // Process-wide shared limiter. VRChat's rate limits are per account, so when
    // several group cores run in one process on the SAME account, they must draw from
    // one budget rather than each assuming it owns the whole thing — otherwise their
    // combined traffic overruns the account's limit and every core thrashes on 429s.
    // Sharing one gate makes them pace cooperatively, and a 429 seen by any core eases
    // the whole account off at once. A single-core launch just creates it once and
    // uses it exactly as a per-instance limiter would, so nothing changes there.
    // (When per-account credentials land, this becomes keyed by account.)
    private static volatile VrcRateLimiter SHARED;
    public static VrcRateLimiter shared(double permitsPerSecond, double burst)
    {
        VrcRateLimiter s = SHARED;
        if (s == null)
        {
            synchronized (VrcRateLimiter.class)
            {
                s = SHARED;
                if (s == null)
                    s = SHARED = new VrcRateLimiter(permitsPerSecond, burst);
            }
        }
        return s;
    }

    /**
     * Blocks until any active global backoff has elapsed and a token is available.
     * On interruption it stops waiting and returns (the caller's request proceeds)
     * rather than failing the call.
     */
    public void acquire()
    {
        while (true)
        {
            long now = System.currentTimeMillis();

            long until = this.backoffUntilMs;
            if (until > now)
            {
                if (!sleep(Math.min(until - now, MAX_SLEEP_CHUNK_MS)))
                    return;
                continue;
            }

            long sleepMs;
            synchronized (this)
            {
                double elapsed = now - this.lastRefillMs;
                if (elapsed > 0)
                {
                    this.available = Math.min(this.maxPermits, this.available + elapsed * this.refillPerMs);
                    this.lastRefillMs = now;
                }
                if (this.available >= 1.0)
                {
                    this.available -= 1.0;
                    return;
                }
                sleepMs = (long) Math.ceil((1.0 - this.available) / this.refillPerMs);
            }
            if (!sleep(Math.max(1L, Math.min(sleepMs, MAX_SLEEP_CHUNK_MS))))
                return;
        }
    }

    /** Applies a global backoff of {@code backoffMs} (e.g. from a 429 / Retry-After). */
    public void penalize(long backoffMs)
    {
        if (backoffMs <= 0)
            return;
        long until = System.currentTimeMillis() + backoffMs;
        this.lastPenaltyMs = System.currentTimeMillis();
        if (until > this.backoffUntilMs)
            this.backoffUntilMs = until;
    }

    /** Milliseconds of backoff still remaining (0 when not backing off). */
    public long backoffRemainingMs()
    {
        return Math.max(0L, this.backoffUntilMs - System.currentTimeMillis());
    }

    /** Whether the client is currently in a global backoff. */
    public boolean isBackingOff()
    {
        return this.backoffRemainingMs() > 0L;
    }

    /** Epoch millis of the most recent penalty, or 0 if none this session. */
    public long lastPenaltyMillis()
    {
        return this.lastPenaltyMs;
    }

    private static boolean sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
            return true;
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
