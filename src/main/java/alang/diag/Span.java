package alang.diag;

/** A source location: 1-based line and column, and the length of the highlighted text. */
public record Span(int line, int col, int length) {
    @Override
    public String toString() {
        return line + ":" + col;
    }
}
