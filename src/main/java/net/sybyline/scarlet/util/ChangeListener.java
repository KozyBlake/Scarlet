package net.sybyline.scarlet.util;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public @FunctionalInterface interface ChangeListener<T>
{

    static <T> ChangeListener<T> wrap(Consumer<T> listener)
    {
        return (previous, next, valid, source) -> { if (valid) listener.accept(next); };
    }

    static <T> ChangeListener<T> wrap(BiConsumer<T, String> listener)
    {
        return (previous, next, valid, source) -> { if (valid) listener.accept(next, source); };
    }

    void onMaybeChange(T previous, T next, boolean valid, String source);

    static <T> ListenerList<T> newListenerList()
    {
        return new ListenerList<>();
    }

    class ListenerList<T> implements ChangeListener<T>
    {
        ListenerList()
        {
        }
        final Set<ChangeListenerHolder<T>> listeners = new ConcurrentSkipListSet<>();
        public boolean register(String source, int priority, boolean listensSelf, ChangeListener<T> listener)
        {
            // Replace any existing listener registered under the same source. Without
            // this, re-registering (e.g. when the settings UI is rebuilt after a
            // relogin/reconnect/theme change) was rejected by the sorted set and left a
            // stale listener bound to a discarded component — so live updates (like a
            // Discord command changing a setting) never reached the visible widget.
            this.listeners.removeIf($ -> $.source.equals(source));
            return this.listeners.add(new ChangeListenerHolder<>(source, priority, listensSelf, listener));
        }
        public boolean unregister(String source)
        {
            return this.listeners.removeIf($ -> $.source.equals(source));
        }
        @Override
        public void onMaybeChange(T previous, T next, boolean valid, String source)
        {
            if (Objects.equals(previous, next))
                return;
            for (ChangeListenerHolder<T> holder : this.listeners)
                if (holder.listensSelf || !holder.source.equals(source))
                    holder.listener.onMaybeChange(previous, next, valid, source);
        }
        
    }

}

class ChangeListenerHolder<T> implements Comparable<ChangeListenerHolder<T>>
{
    ChangeListenerHolder(String source, int priority, boolean listensSelf, ChangeListener<T> listener)
    {
        this.source = source;
        this.priority = priority;
        this.listensSelf = listensSelf;
        this.listener = listener;
    }
    final String source;
    final int priority;
    final boolean listensSelf;
    final ChangeListener<T> listener;
    @Override
    public int compareTo(ChangeListenerHolder<T> other)
    {
        // Tie-break by source. The backing ConcurrentSkipListSet decides element
        // identity purely from compareTo, so comparing on priority alone made any two
        // listeners with the same priority (every listener registers at priority 0)
        // look "equal", silently dropping all but the first. Tie-breaking by source
        // keeps this consistent with equals()/hashCode(), which key on source.
        int cmp = Integer.compare(this.priority, other.priority);
        return cmp != 0 ? cmp : this.source.compareTo(other.source);
    }
    @Override
    public int hashCode()
    {
        return this.source.hashCode();
    }
    @Override
    public boolean equals(Object obj)
    {
        return this == obj || (obj instanceof ChangeListenerHolder && this.source.equals(((ChangeListenerHolder<?>)obj).source));
    }
}
