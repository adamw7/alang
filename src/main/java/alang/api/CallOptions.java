package alang.api;

import alang.interp.Interpreter;

/**
 * The limits of one tool call. Immutable: each setter returns a new instance.
 */
public final class CallOptions {
    private final long maxSteps;
    private final long timeoutMillis;
    private final Cancellation cancellation;
    private final Host host;

    private CallOptions(long maxSteps, long timeoutMillis, Cancellation cancellation, Host host) {
        this.maxSteps = maxSteps;
        this.timeoutMillis = timeoutMillis;
        this.cancellation = cancellation;
        this.host = host;
    }

    /** The defaults of `alang call`: the default step budget and a time limit of 60 s. */
    public static CallOptions defaults() {
        return new CallOptions(Interpreter.DEFAULT_MAX_STEPS, 60_000, null, null);
    }

    /**
     * The step budget: one per statement, plus the data it goes through. A call that uses it up ends with
     * error code R0011. 0 means no limit.
     */
    public CallOptions maxSteps(long steps) {
        if (steps < 0) throw new IllegalArgumentException("maxSteps must be 0 (no limit) or more");
        return new CallOptions(steps, timeoutMillis, cancellation, host);
    }

    /** A wall-clock backstop to the step budget: the call ends with error code "timeout" after it. 0: none. */
    public CallOptions timeoutMillis(long millis) {
        if (millis < 0) throw new IllegalArgumentException("timeoutMillis must be 0 (no limit) or more");
        return new CallOptions(maxSteps, millis, cancellation, host);
    }

    /** A handle another thread can use to stop the call; it then ends with error code "cancelled". */
    public CallOptions cancellation(Cancellation c) {
        return new CallOptions(maxSteps, timeoutMillis, c, host);
    }

    /**
     * What answers the calls of the program's `host fn`s. Without one, such a call ends the tool call with trap R0015.
     * The time limit does not stop a host while it answers: give it a limit of its own.
     */
    public CallOptions host(Host h) {
        return new CallOptions(maxSteps, timeoutMillis, cancellation, h);
    }

    /** The host, or null. */
    public Host host() {
        return host;
    }

    public long maxSteps() {
        return maxSteps;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    /** The cancellation handle, or null. */
    public Cancellation cancellation() {
        return cancellation;
    }
}
