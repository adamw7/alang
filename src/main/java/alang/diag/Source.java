package alang.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of one ALang source file. Columns and lengths of a {@link Span} count Unicode code points, as
 * the lexer does, not the UTF-16 chars of a Java string: an emoji is one column.
 */
public final class Source {
    public final String path;
    public final String text;
    private final List<String> lines = new ArrayList<>();

    public Source(String path, String text) {
        this.path = path;
        this.text = text;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(stripCr(text.substring(start, i)));
                start = i + 1;
            }
        }
        lines.add(stripCr(text.substring(start)));
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    /** The number of lines; text that ends with a line break has an empty last line. */
    public int lineCount() {
        return lines.size();
    }

    /** Returns the given 1-based line, or "" if out of range. */
    public String line(int n) {
        return n >= 1 && n <= lines.size() ? lines.get(n - 1) : "";
    }

    /** The source text a span covers (as much of it as its line has), or "" for none. */
    public String text(Span s) {
        String line = line(s.line());
        int start = index(line, Math.max(0, s.col() - 1));
        int end = index(line, Math.max(0, s.col() - 1) + Math.max(0, s.length()));
        return line.substring(start, end);
    }

    /**
     * The marker line under a span: blanks up to its column, keeping the tabs of the source line so that
     * the carets line up with it, then one caret per column (at least one).
     */
    public String caret(Span s) {
        int[] cps = line(s.line()).codePoints().toArray();
        int col = Math.max(1, s.col());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < col - 1 && i < cps.length; i++) sb.append(cps[i] == '\t' ? '\t' : ' ');
        int len = Math.max(1, Math.min(s.length(), cps.length - col + 1));
        return sb.append("^".repeat(len)).toString();
    }

    /** The UTF-16 index of the code point at {@code column} (0-based), or the line's end. */
    private static int index(String line, int column) {
        int n = line.codePointCount(0, line.length());
        return column >= n ? line.length() : line.offsetByCodePoints(0, column);
    }

    /** The length of a token's text in columns (code points). */
    public static int columns(String text) {
        return text.codePointCount(0, text.length());
    }
}
