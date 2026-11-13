package alang.diag;

import java.util.List;

/**
 * One compiler message. {@code hint} is an actionable suggestion, or null; {@code fixes} are the edits that
 * make the change the hint describes, where it is mechanical (`alang fix` applies them), or none.
 */
public record Diagnostic(Code code, String message, Span span, String hint, List<Edit> fixes) {
    public Diagnostic(Code code, String message, Span span, String hint) {
        this(code, message, span, hint, List.of());
    }

    public boolean isError() {
        return !code.isWarning();
    }
}
