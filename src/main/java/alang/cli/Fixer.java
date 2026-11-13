package alang.cli;

import alang.diag.Diagnostic;
import alang.diag.Edit;
import alang.diag.Source;
import alang.diag.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * `alang fix`: applies the edits of every diagnostic that has a mechanical fix, then checks the file again,
 * since a fix can let the parser reach the next error, until no fix is left. Only the file given is changed;
 * a diagnostic in a file it imports keeps its fix for `alang fix` on that file.
 */
final class Fixer {
    private Fixer() {}

    /** At most this many rounds of checking and fixing: each fixes what the one before let the checker reach. */
    static final int MAX_ROUNDS = 32;

    /** A diagnostic that was fixed, and where it was: its line and column in the text of that round. */
    record Fixed(Diagnostic diagnostic, String description) {}

    /** The fixed text, the fixes made, and the diagnostics of the fixed text. */
    record Outcome(String text, List<Fixed> fixed, Frontend.Result result) {}

    static Outcome run(Source src, Function<Source, Frontend.Result> compile) {
        String text = src.text;
        List<Fixed> fixed = new ArrayList<>();
        Frontend.Result r;
        for (int round = 0; ; round++) {
            Source cur = new Source(src.path, text);
            r = compile.apply(cur);
            if (round == MAX_ROUNDS) break;
            List<Diagnostic> chosen = new ArrayList<>();
            List<int[]> taken = new ArrayList<>();
            List<Edit> edits = new ArrayList<>();
            for (Diagnostic d : r.diags().all()) {
                if (d.fixes().isEmpty() || d.fixes().stream().anyMatch(e -> e.span().file() != null && e.span().file() != cur)) continue;
                List<int[]> ranges = new ArrayList<>();
                for (Edit e : d.fixes()) ranges.add(range(text, e.span()));
                // A diagnostic's edits go in together or not at all; one that overlaps another's waits a round.
                if (ranges.stream().anyMatch(a -> taken.stream().anyMatch(b -> overlap(a, b)))) continue;
                taken.addAll(ranges);
                edits.addAll(d.fixes());
                chosen.add(d);
            }
            if (chosen.isEmpty()) break;
            String next = apply(text, edits);
            if (next.equals(text)) break;
            for (Diagnostic d : chosen) {
                List<String> parts = new ArrayList<>();
                for (Edit e : d.fixes()) parts.add(e.describe(cur));
                fixed.add(new Fixed(d, String.join(", then ", parts)));
            }
            text = next;
        }
        return new Outcome(text, fixed, r);
    }

    /** {@code text} with the edits made; they do not overlap, and each stays on its line. */
    static String apply(String text, List<Edit> edits) {
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt((Edit e) -> range(text, e.span())[0]));
        StringBuilder sb = new StringBuilder(text.length() + 64);
        int at = 0;
        for (Edit e : sorted) {
            int[] r = range(text, e.span());
            sb.append(text, at, r[0]).append(e.replacement());
            at = r[1];
        }
        return sb.append(text.substring(at)).toString();
    }

    /** The UTF-16 offsets [start, end) of a span's text; columns count code points, and a span never leaves its line. */
    static int[] range(String text, Span s) {
        int lineStart = 0;
        for (int line = 1; line < s.line(); line++) {
            int nl = text.indexOf('\n', lineStart);
            if (nl < 0) return new int[] {text.length(), text.length()};
            lineStart = nl + 1;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) lineEnd = text.length();
        if (lineEnd > lineStart && text.charAt(lineEnd - 1) == '\r') lineEnd--;
        int start = advance(text, lineStart, lineEnd, Math.max(0, s.col() - 1));
        int end = advance(text, start, lineEnd, Math.max(0, s.length()));
        return new int[] {start, end};
    }

    private static int advance(String text, int from, int limit, int codePoints) {
        int i = from;
        for (int k = 0; k < codePoints && i < limit; k++) i += Character.charCount(text.codePointAt(i));
        return Math.min(i, limit);
    }

    /** Whether two ranges overlap; two insertions at one place count as overlapping, an insertion at an edge does not. */
    private static boolean overlap(int[] a, int[] b) {
        if (a[0] == a[1] && b[0] == b[1]) return a[0] == b[0];
        return a[0] < b[1] && b[0] < a[1];
    }
}
