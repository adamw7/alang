package alang.diag;

import java.util.ArrayList;
import java.util.List;

/** The text of one ALang source file. */
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

    /** Returns the given 1-based line, or "" if out of range. */
    public String line(int n) {
        return n >= 1 && n <= lines.size() ? lines.get(n - 1) : "";
    }
}
