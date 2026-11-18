package alang.api;

/**
 * One compiler message, as `alang check --json` reports it.
 *
 * @param severity   error or warning
 * @param code       the stable code, e.g. "E0201"; `alang explain CODE` explains it
 * @param title      the code's short title
 * @param message    what is wrong, here
 * @param file       the file it is in: the compiled one, or one it imports
 * @param line       1-based
 * @param column     1-based, in characters
 * @param length     the length of the text it points at
 * @param sourceLine that line of the file
 * @param hint       how to fix it, or null
 */
public record Diagnostic(Severity severity, String code, String title, String message, String file, int line, int column,
                         int length, String sourceLine, String hint) {
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return file + ":" + line + ":" + column + ": " + severity.name().toLowerCase(java.util.Locale.ROOT) + "[" + code + "]: "
            + message;
    }
}
