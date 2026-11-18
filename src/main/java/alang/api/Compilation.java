package alang.api;

import alang.cli.Frontend;
import alang.diag.Source;
import alang.util.Json;
import java.util.*;

/** The outcome of compiling a program: its diagnostics and, when there are no errors, the program. */
public final class Compilation {
    private final Frontend.Result result;
    private final List<Diagnostic> diagnostics;

    Compilation(Frontend.Result result) {
        this.result = result;
        List<Diagnostic> list = new ArrayList<>();
        Source main = result.diags().source();
        for (alang.diag.Diagnostic d : result.diags().all()) {
            Source file = d.span().file() == null ? main : d.span().file();
            list.add(new Diagnostic(d.isError() ? Severity.ERROR : Severity.WARNING, d.code().name(), d.code().title,
                d.message(), file.path, d.span().line(), d.span().col(), d.span().length(), file.line(d.span().line()),
                d.hint()));
        }
        this.diagnostics = List.copyOf(list);
    }

    /** Whether the program compiled: no errors, though there may be warnings. */
    public boolean ok() {
        return result.ok();
    }

    /** Every diagnostic, errors and warnings, in the order `alang check` reports them. */
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    /** The diagnostics as `alang check` prints them, with the source line, a caret and the hint. */
    public String diagnosticsText() {
        return result.diags().renderText();
    }

    /** The diagnostics as the JSON array `alang check --json` reports, with each mechanical fix. */
    public String diagnosticsJson() {
        return Json.write(result.diags().toJson());
    }

    /** The compiled program; throws a CompileException, which carries the diagnostics, when it has errors. */
    public Program program() {
        if (!ok()) {
            throw new CompileException("the program has " + result.diags().errorCount() + " error(s):\n" + diagnosticsText(),
                diagnostics);
        }
        return new Program(result.diags().source(), result.program());
    }
}
