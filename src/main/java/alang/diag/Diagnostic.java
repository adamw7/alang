package alang.diag;

/** One compiler message. {@code hint} is an actionable suggestion, or null. */
public record Diagnostic(Code code, String message, Span span, String hint) {
    public boolean isError() {
        return !code.isWarning();
    }
}
