package alang.interp;

import alang.diag.Code;
import alang.diag.Span;

/**
 * What a run costs, in steps, against its budget (`--max-steps`), and a way for another thread to stop it.
 *
 * <p>A statement or a loop iteration is a step. Work on data is counted in units, {@link #UNITS_PER_STEP} to
 * a step: a byte of a str, or a slot of a list, array, map or set that an operation makes, moves or clears
 * in bulk, is 1 unit; an element, field or entry that it handles one at a time is {@link #ELEMENT}; a
 * comparison of a sort, writing a number as text, and the float functions cost what they take (see the
 * constants). The weights make a step of work take about as long as a simple statement, measured on the
 * reference interpreter, so the budget bounds the time a run takes, not only the statements it runs. The
 * count depends only on the program and its input: the same run costs the same steps every time, and traps
 * at the same point when it runs out.
 *
 * <p>{@link #stop} is the exception: a host calls it from another thread, when the run's time is up or its
 * caller no longer wants the result, and the run then ends with {@link Stopped} at its next step. It is the
 * only part of a run that another thread touches.
 */
public final class Meter {
    /** The units of work that make one step. */
    public static final long UNITS_PER_STEP = 128;

    /** The units of an element, field, entry or value handled on its own: copied, compared or written. */
    static final long ELEMENT = 16;

    /** The units of one comparison of a sort, with the moves around it. */
    static final long COMPARISON = 56;

    /** The units of writing an integer as text. */
    static final long INT_TEXT = 128;

    /** The units of a float function: sqrt, sin, exp, pow and the like. */
    static final long FLOAT_MATH = 128;

    /**
     * The units of writing a float as text, which works out its exact decimal value first: more digits, and
     * so more work, the further its binary exponent is from 0. Zero, nan and the infinities are written as they are.
     */
    static long floatText(double d) {
        if (d == 0 || !Double.isFinite(d)) return INT_TEXT;
        return 2816 + 22L * Math.abs(Math.getExponent(d));
    }

    private final long maxSteps;
    private long steps;
    /** Units not yet counted as a step: always less than UNITS_PER_STEP between charges. */
    private long units;
    /** The statement being run, or the call being made: where an exhausted budget traps. */
    private Span at;
    /** Whether a line is being printed: its work is counted, and the budget applies once it is out. */
    private boolean holding;
    /** Set by {@link #stop}, from any thread; read at every step. */
    private volatile Stopped stopped;

    /** A meter for a run of at most {@code maxSteps} steps; 0 means no budget. */
    public Meter(long maxSteps) {
        this.maxSteps = maxSteps;
    }

    /** The steps so far. */
    public long steps() {
        return steps;
    }

    /**
     * Stops the run at its next step with {@link Stopped}, {@code code} ("timeout" or "cancelled") and
     * {@code message}. Safe to call from any thread, at any time, and more than once: the first reason is kept.
     */
    public synchronized void stop(String code, String message) {
        if (stopped == null) stopped = new Stopped(code, message);
    }

    /** Where an exhausted budget traps until the next statement starts. */
    void at(Span s) {
        at = s;
    }

    Span at() {
        return at;
    }

    /** A statement or a loop iteration at {@code s}. */
    void step(Span s) {
        at = s;
        steps++;
        check();
    }

    /** {@code n} units of work on data. */
    void units(long n) {
        units += n;
        if (units >= UNITS_PER_STEP) {
            steps += units / UNITS_PER_STEP;
            units %= UNITS_PER_STEP;
            if (!holding) check();
        }
    }

    /**
     * Counts the work that follows without stopping the run, until {@link #release}: print() writes its line
     * whole, so that a trap never leaves half a line. A line costs what its value costs, which the memory
     * bound limits.
     */
    void hold() {
        holding = true;
    }

    /** Ends {@link #hold}: the run stops here if the work since then has used up its budget. */
    void release() {
        holding = false;
        check();
    }

    /** {@code n} bytes made, copied, compared, searched or written. */
    void bytes(long n) {
        units(n);
    }

    /** {@code n} slots of a list, array, map or set made, moved or cleared in bulk. */
    void slots(long n) {
        units(n);
    }

    /** {@code n} elements, fields, entries or values handled one at a time. */
    void elements(long n) {
        units(n * ELEMENT);
    }

    private void check() {
        if (steps > maxSteps && maxSteps > 0) {
            throw new Trap(Code.R0011, at, "step budget of " + maxSteps + " steps exhausted");
        }
        Stopped s = stopped;
        if (s != null) throw s;
    }
}
