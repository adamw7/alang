package alang.diag;

/**
 * One change to a source file that fixes a diagnostic: the text {@code span} covers, on one line, becomes
 * {@code replacement}. A span of length 0 inserts the replacement before its column, and one that ends with a line
 * break and the indentation of the line inserts a line above it; an empty replacement deletes. `alang fix` applies
 * the edits of every diagnostic that has them.
 */
public record Edit(Span span, String replacement) {
    /** What the edit does, for a person: replace `&&` with `and`, insert `;`, delete `mut `. */
    public String describe(Source source) {
        Source file = span.file() == null ? source : span.file();
        String old = span.length() == 0 ? "" : file.text(span);
        int nl = replacement.indexOf('\n');
        if (old.isEmpty() && nl >= 0 && replacement.substring(nl + 1).isBlank()) {
            return "insert the line `" + replacement.substring(0, nl) + "` above";
        }
        if (old.isEmpty()) return "insert `" + replacement + "`";
        if (replacement.isEmpty()) return "delete `" + old + "`";
        return "replace `" + old + "` with `" + replacement + "`";
    }
}
