package alang.diag;

/**
 * A source location: the file, 1-based line and column, and the length of the highlighted text. A program
 * can import other files, so a span names its own; {@code file} is null only for code that was never
 * parsed from a file.
 */
public record Span(int line, int col, int length, Source file) {
    @Override
    public String toString() {
        return line + ":" + col;
    }

    /** "line:col" in the given file, or "path:line:col" when the span is in another file. */
    public String where(Source from) {
        return file == null || file == from ? toString() : file.path + ":" + this;
    }
}
