package alang.interp;

/**
 * A run that the host stopped through its {@link Meter}: its time ran out, or its caller cancelled it. Not a
 * trap: where it happens depends on the clock, so the same run may finish, or stop elsewhere, the next time.
 */
public final class Stopped extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Why: "timeout" or "cancelled", as `alang call` and `alang serve` report it. */
    public final String code;

    public Stopped(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }
}
