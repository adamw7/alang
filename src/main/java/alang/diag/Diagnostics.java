package alang.diag;

import alang.util.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Collects diagnostics for one source file and renders them for humans or machines. */
public final class Diagnostics {
    private final Source source;
    private final List<Diagnostic> list = new ArrayList<>();

    public Diagnostics(Source source) {
        this.source = source;
    }

    public Source source() {
        return source;
    }

    public void error(Code code, Span span, String message) {
        add(code, span, message, null);
    }

    public void error(Code code, Span span, String message, String hint) {
        add(code, span, message, hint);
    }

    public void warning(Code code, Span span, String message, String hint) {
        add(code, span, message, hint);
    }

    public void add(Code code, Span span, String message, String hint) {
        // Suppress exact duplicates (same code at same place), which can come from error recovery.
        for (Diagnostic d : list) {
            if (d.code() == code && d.span().equals(span) && d.message().equals(message)) return;
        }
        list.add(new Diagnostic(code, message, span, hint));
    }

    public List<Diagnostic> all() {
        List<Diagnostic> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt((Diagnostic d) -> d.span().line()).thenComparingInt(d -> d.span().col()));
        return sorted;
    }

    public boolean hasErrors() {
        return list.stream().anyMatch(Diagnostic::isError);
    }

    public int errorCount() {
        return (int) list.stream().filter(Diagnostic::isError).count();
    }

    public int warningCount() {
        return list.size() - errorCount();
    }

    /** Renders diagnostics in a rustc-like format with a source excerpt and caret. */
    public String renderText() {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : all()) {
            renderOne(sb, source, d);
        }
        return sb.toString();
    }

    public static void renderOne(StringBuilder sb, Source source, Diagnostic d) {
        String kind = d.isError() ? "error" : "warning";
        sb.append(kind).append('[').append(d.code().name()).append("]: ").append(d.message()).append('\n');
        Span s = d.span();
        if (s.line() > 0) {
            String lineNo = String.valueOf(s.line());
            String pad = " ".repeat(lineNo.length());
            sb.append(pad).append("--> ").append(source.path).append(':').append(s.line()).append(':').append(s.col()).append('\n');
            sb.append(pad).append(" |\n");
            String text = source.line(s.line());
            sb.append(lineNo).append(" | ").append(text).append('\n');
            int col = Math.max(1, s.col());
            int len = Math.max(1, Math.min(s.length(), Math.max(1, text.length() - col + 1)));
            StringBuilder caretPad = new StringBuilder();
            for (int i = 0; i < col - 1 && i < text.length(); i++) {
                caretPad.append(text.charAt(i) == '\t' ? '\t' : ' ');
            }
            sb.append(pad).append(" | ").append(caretPad).append("^".repeat(len)).append('\n');
        }
        if (d.hint() != null) {
            sb.append("  = hint: ").append(d.hint()).append('\n');
        }
        sb.append('\n');
    }

    public List<Object> toJson() {
        List<Object> out = new ArrayList<>();
        for (Diagnostic d : all()) {
            out.add(toJson(source, d));
        }
        return out;
    }

    public static Map<String, Object> toJson(Source source, Diagnostic d) {
        return Json.obj(
            "severity", d.isError() ? "error" : "warning",
            "code", d.code().name(),
            "title", d.code().title,
            "message", d.message(),
            "file", source.path,
            "line", d.span().line(),
            "column", d.span().col(),
            "length", d.span().length(),
            "source_line", source.line(d.span().line()),
            "hint", d.hint());
    }
}
