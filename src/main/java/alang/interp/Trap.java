package alang.interp;

import alang.diag.Code;
import alang.diag.Span;
import java.util.ArrayList;
import java.util.List;

/** A runtime error. ALang has no undefined behavior: every failure is a trap with a code. */
public final class Trap extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final Code code;
    public final Span span;
    /** Call stack, innermost first: "fn name (line:col)". */
    public final List<String> trace = new ArrayList<>();

    public Trap(Code code, Span span, String message) {
        super(message, null, false, false);
        this.code = code;
        this.span = span;
    }
}
