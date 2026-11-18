package alang.api;

import alang.interp.Meter;
import java.util.*;

/**
 * Stops a call from another thread: pass it in {@link CallOptions#cancellation}, then {@link #cancel}. A call
 * cancelled before it starts ends at once. One handle may serve several calls; cancelling stops them all.
 */
public final class Cancellation {
    private final Set<Meter> running = new HashSet<>();
    private boolean cancelled;

    private Cancellation() {}

    /** A new handle, not yet cancelled. */
    public static Cancellation create() {
        return new Cancellation();
    }

    /** Stops every call that uses this handle, now and later. */
    public synchronized void cancel() {
        cancelled = true;
        for (Meter m : running) m.stop("cancelled", "the host cancelled the call");
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    synchronized void attach(Meter m) {
        running.add(m);
        if (cancelled) m.stop("cancelled", "the host cancelled the call");
    }

    synchronized void detach(Meter m) {
        running.remove(m);
    }
}
