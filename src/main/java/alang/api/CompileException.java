package alang.api;

import java.util.List;

/** Thrown by {@link Compilation#program()} for a program that did not compile. */
public final class CompileException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<Diagnostic> diagnostics;

    CompileException(String message, List<Diagnostic> diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    /** Every diagnostic of the compilation, errors and warnings. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
