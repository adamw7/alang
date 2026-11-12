package alang.diag;

import alang.util.Json;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Collects the diagnostics of a program and renders them for humans or machines. The program is the file
 * given on the command line, its {@link #source()}, and the files it imports, which {@link #addFile} adds
 * in the order they are read.
 */
public final class Diagnostics {
    private final Source source;
    private final List<Source> files = new ArrayList<>();
    private final List<Diagnostic> list = new ArrayList<>();

    public Diagnostics(Source source) {
        this.source = source;
        files.add(source);
    }

    /** The file given on the command line. */
    public Source source() {
        return source;
    }

    /** Adds an imported file; its diagnostics come after those of the files added before it. */
    public void addFile(Source file) {
        if (!files.contains(file)) files.add(file);
    }

    /** The program's files: the one given on the command line, then the imported ones in the order they were read. */
    public List<Source> files() {
        return List.copyOf(files);
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
        sorted.sort(Comparator.comparingInt((Diagnostic d) -> fileOrder(d.span()))
            .thenComparingInt(d -> d.span().line()).thenComparingInt(d -> d.span().col()));
        return sorted;
    }

    private int fileOrder(Span s) {
        int i = files.indexOf(s.file());
        return i < 0 ? 0 : i;
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

    /** One diagnostic as text; it shows the file of its span, else {@code source}. */
    public static void renderOne(StringBuilder sb, Source source, Diagnostic d) {
        String kind = d.isError() ? "error" : "warning";
        sb.append(kind).append('[').append(d.code().name()).append("]: ").append(d.message()).append('\n');
        Span s = d.span();
        if (s.file() != null) source = s.file();
        if (s.line() > 0) {
            String lineNo = String.valueOf(s.line());
            String pad = " ".repeat(lineNo.length());
            sb.append(pad).append("--> ").append(source.path).append(':').append(s.line()).append(':').append(s.col()).append('\n');
            sb.append(pad).append(" |\n");
            sb.append(lineNo).append(" | ").append(source.line(s.line())).append('\n');
            sb.append(pad).append(" | ").append(source.caret(s)).append('\n');
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

    /**
     * One diagnostic as JSON. The report around it names the file given on the command line once, so a
     * diagnostic in that file does not repeat it; one in an imported file has a "file" key, its path.
     */
    public static Map<String, Object> toJson(Source source, Diagnostic d) {
        Source file = d.span().file() == null ? source : d.span().file();
        Map<String, Object> j = Json.obj(
            "severity", d.isError() ? "error" : "warning",
            "code", d.code().name(),
            "title", d.code().title,
            "message", d.message());
        if (file != source) j.put("file", file.path);
        j.put("line", d.span().line());
        j.put("column", d.span().col());
        j.put("length", d.span().length());
        j.put("source_line", file.line(d.span().line()));
        j.put("hint", d.hint());
        return j;
    }
}
