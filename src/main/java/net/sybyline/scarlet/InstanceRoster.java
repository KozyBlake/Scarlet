package net.sybyline.scarlet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tracks who is present in the current VRChat instance and the order in which
 * per-player UI updates (join/leave) are applied.
 *
 * <p>During log catch-up (preamble), UI updates are <em>deferred</em> and flushed
 * in log order once catch-up completes; live updates apply immediately. Crucially,
 * both joins and leaves go through the same {@link #runOrDefer} path, so a queued
 * join can never flush <em>after</em> a leave and resurrect a departed player —
 * that inversion was the cause of the inflated "present" count. Because the policy
 * lives here and is applied identically to joins and leaves, the bug is structural
 * impossible to reintroduce from the caller.
 *
 * <p>This class is deliberately dependency-free and UI-agnostic (updates are opaque
 * {@link Runnable}s), so the join/leave/present state machine is unit-testable
 * without a running Scarlet. It is thread-safe; UI actions are never invoked while
 * the internal lock is held.
 */
public final class InstanceRoster
{
    private final Map<String, String> present = new LinkedHashMap<>(); // userId -> displayName
    private final List<Runnable> pending = new ArrayList<>();

    /** Whether the given user is currently in the instance. */
    public boolean isPresent(String userId)
    {
        if (userId == null)
            return false;
        synchronized (this)
        {
            return this.present.containsKey(userId);
        }
    }

    /** Display name last seen for a present user, or null. */
    public String displayName(String userId)
    {
        synchronized (this)
        {
            return this.present.get(userId);
        }
    }

    /** Display name for a present user, or {@code fallback} if not present. */
    public String displayNameOr(String userId, String fallback)
    {
        synchronized (this)
        {
            String name = this.present.get(userId);
            return name != null ? name : fallback;
        }
    }

    /** Number of users currently present. */
    public int presentCount()
    {
        synchronized (this)
        {
            return this.present.size();
        }
    }

    /** Snapshot of the currently-present user ids (insertion order). */
    public Set<String> presentUserIds()
    {
        synchronized (this)
        {
            return new LinkedHashSet<>(this.present.keySet());
        }
    }

    /** Records a join and schedules its UI update (deferred during preamble). */
    public void join(String userId, String displayName, boolean preamble, Runnable uiUpdate)
    {
        if (userId != null)
            synchronized (this)
            {
                this.present.put(userId, displayName);
            }
        this.runOrDefer(preamble, uiUpdate);
    }

    /**
     * Records a leave and schedules its UI update. During preamble this is deferred
     * exactly like {@link #join}, so join/leave for the same player flush in log
     * order rather than the leave running early and being undone by a late join.
     */
    public void leave(String userId, boolean preamble, Runnable uiUpdate)
    {
        if (userId != null)
            synchronized (this)
            {
                this.present.remove(userId);
            }
        this.runOrDefer(preamble, uiUpdate);
    }

    /** Runs a UI action now (live) or queues it to flush in order (preamble). */
    public void runOrDefer(boolean preamble, Runnable action)
    {
        if (action == null)
            return;
        if (preamble)
        {
            synchronized (this)
            {
                this.pending.add(action);
            }
        }
        else
        {
            action.run();
        }
    }

    /**
     * Applies all queued updates in insertion (log) order, clearing the queue.
     * Runnables run outside the lock; a failing one is reported to {@code onError}
     * (may be null) and does not stop the rest.
     */
    public void flush(Consumer<Throwable> onError)
    {
        List<Runnable> copy;
        synchronized (this)
        {
            copy = new ArrayList<>(this.pending);
            this.pending.clear();
        }
        for (Runnable action : copy)
        {
            try
            {
                action.run();
            }
            catch (Throwable ex)
            {
                if (onError != null)
                    onError.accept(ex);
            }
        }
    }

    /** Drops queued updates without running them (e.g. leaving an instance mid-catch-up). */
    public void clearPending()
    {
        synchronized (this)
        {
            this.pending.clear();
        }
    }

    /** Number of currently-queued (not yet flushed) updates. */
    public int pendingCount()
    {
        synchronized (this)
        {
            return this.pending.size();
        }
    }

    /** Clears all present players and queued updates. */
    public void reset()
    {
        synchronized (this)
        {
            this.present.clear();
            this.pending.clear();
        }
    }
}
