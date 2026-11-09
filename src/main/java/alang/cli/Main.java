package alang.cli;

import alang.ast.Ast.FnDecl;
import alang.ast.Ast.LocalVar;
import alang.ast.Ast.Program;
import alang.ast.Ast.TestDecl;
import alang.ast.Builtin;
import alang.cgen.CGen;
import alang.check.MemoryReport;
import alang.diag.Code;
import alang.diag.Source;
import alang.diag.Span;
import alang.interp.Interpreter;
import alang.interp.JsonValues;
import alang.interp.Trap;
import alang.types.Type;
import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The `alang` command line tool. */
public final class Main {
    public static final String VERSION = "0.1.0";

    private static final String USAGE = """
        ALang %s - an agent-first, heap-free programming language

        usage: alang <command> [options]

        commands:
          check <file.al>              type-check; report diagnostics and the static memory bound
          run <file.al>                check, then run fn main()
          test <file.al>               check, then run every `test "..." { }` block
          tools <file.al>              print JSON schemas of all `tool fn` functions
          call <file.al> <tool> [json] call a tool with a JSON object of arguments (or read it from stdin)
          build <file.al> [-o out.c]   compile to heap-free C99 (no malloc; stack and static data only)
          explain <CODE>               explain a diagnostic or trap code, e.g. E0201 (or --all, markdown)
          grammar                      print the path of the ANTLR grammar, grammar/ALang.g4
          version                      print the version

        options:
          --json             machine-readable output (check, run, test)
          --max-steps N      interpreter statement budget (default %d, 0 = unlimited)
          --max-stack BYTES  fail `check` if any entry point may need more stack
          --filter TEXT      only run tests whose name contains TEXT
          --memory           print the per-function memory table (check)
          -o FILE            output file (build)

        exit codes: 0 success, 1 errors / traps / failed tests, 2 usage error
        """;

    private final PrintStream out;
    private final PrintStream err;

    private boolean json;
    private boolean memoryTable;
    private long maxSteps = Interpreter.DEFAULT_MAX_STEPS;
    private long maxStack;
    private String filter;
    private String output;
    private final List<String> positional = new ArrayList<>();

    Main(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = new Main(out, err).run(args);
        out.flush();
        System.exit(code);
    }

    int run(String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(String.format(Locale.ROOT, USAGE, VERSION, Interpreter.DEFAULT_MAX_STEPS));
            return args.length == 0 ? 2 : 0;
        }
        String cmd = args[0];
        try {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--json" -> json = true;
                    case "--memory" -> memoryTable = true;
                    case "--max-steps" -> maxSteps = Long.parseLong(next(args, ++i, a));
                    case "--max-stack" -> maxStack = Long.parseLong(next(args, ++i, a));
                    case "--filter" -> filter = next(args, ++i, a);
                    case "-o" -> output = next(args, ++i, a);
                    default -> {
                        if (a.startsWith("--")) throw new UsageException("unknown option " + a);
                        positional.add(a);
                    }
                }
            }
            return switch (cmd) {
                case "check" -> check(file());
                case "run" -> runMain(file());
                case "test" -> test(file());
                case "tools" -> tools(file());
                case "call" -> call(file());
                case "build" -> build(file());
                case "explain" -> explain();
                case "grammar" -> {
                    line(out, "grammar/ALang.g4 (ANTLR 4) in the ALang repository");
                    yield 0;
                }
                case "version", "--version" -> {
                    line(out, "alang " + VERSION);
                    yield 0;
                }
                default -> throw new UsageException("unknown command `" + cmd + "`; run `alang help`");
            };
        } catch (UsageException e) {
            line(err, "alang: " + e.getMessage());
            return 2;
        } catch (NumberFormatException e) {
            line(err, "alang: expected a number: " + e.getMessage());
            return 2;
        } catch (StackOverflowError e) {
            // The parser, checker and interpreter recurse over the syntax tree; extremely deep
            // nesting (hundreds of levels) exhausts the thread stack. Report it instead of crashing.
            out.flush();
            line(err, "alang: the program is nested too deeply to process; split the deepest expression or block "
                + "into smaller parts using intermediate `let` bindings or helper functions");
            return 1;
        }
    }

    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String m) {
            super(m);
        }
    }

    private static String next(String[] args, int i, String opt) {
        if (i >= args.length) throw new UsageException(opt + " needs a value");
        return args[i];
    }

    private Source file() {
        if (positional.isEmpty()) throw new UsageException("missing source file");
        Path p = Path.of(positional.get(0));
        try {
            return new Source(p.toString(), Files.readString(p, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            throw new UsageException("file not found: " + p);
        } catch (IOException e) {
            throw new UsageException("cannot read " + p + ": " + e.getMessage());
        }
    }

    // =================================================================== check

    private Frontend.Result compile(Source src) {
        return Frontend.compile(src, maxStack);
    }

    /** Prints diagnostics in the requested format. Returns true if compilation succeeded. */
    private boolean report(Frontend.Result r, Map<String, Object> jsonOut) {
        if (json) {
            jsonOut.put("diagnostics", r.diags().toJson());
        } else {
            err.print(r.diags().renderText());
        }
        return r.ok();
    }

    private int check(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("ok", r.ok());
        j.put("file", src.path);
        j.put("errors", r.diags().errorCount());
        j.put("warnings", r.diags().warningCount());
        report(r, j);
        MemoryReport mem = r.ok() ? r.check().memory() : null;
        if (json) {
            j.put("memory", mem == null ? null : mem.toJson());
            line(out, Json.pretty(j));
            return r.ok() ? 0 : 1;
        }
        if (!r.ok()) {
            line(err, "check failed: " + r.diags().errorCount() + " error(s), " + r.diags().warningCount() + " warning(s)");
            return 1;
        }
        Program p = r.program();
        line(out, "ok: " + src.path + ": " + p.itemsOf(FnDecl.class).size() + " function(s), "
            + p.itemsOf(TestDecl.class).size() + " test(s), " + r.diags().warningCount() + " warning(s)");
        if (mem != null) {
            line(out, "memory: heap 0 B, static " + mem.staticBytes + " B, worst-case stack " + mem.maxStack() + " B");
            if (memoryTable) {
                out.print(String.format(Locale.ROOT, "  %-40s %12s %12s\n", "routine", "frame (B)", "stack (B)"));
                for (MemoryReport.Entry e : mem.routines) {
                    out.print(String.format(Locale.ROOT, "  %-40s %12d %12d%s\n", e.label(), e.frameBytes(),
                        e.stackBytes(), mem.roots.contains(e) ? "  (entry)" : ""));
                }
            }
        }
        return 0;
    }

    // =================================================================== run

    private int runMain(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("ok", false);
        if (!report(r, j)) {
            return failCompile(r, j);
        }
        FnDecl main = r.program().function("main");
        if (main == null) {
            String msg = "no `fn main()` in " + src.path + "; use `alang test` for tests or `alang call` for tools";
            if (json) {
                j.put("ok", false);
                j.put("error", msg);
                line(out, Json.pretty(j));
            } else {
                line(err, "alang: " + msg);
            }
            return 1;
        }
        ByteArrayOutputStream captured = json ? new ByteArrayOutputStream() : null;
        OutputStream sink = json ? captured : out;
        Interpreter in = new Interpreter(src, sink, maxSteps);
        Trap trap = null;
        try {
            in.call(main, new Object[0], main.nameSpan);
        } catch (Trap t) {
            trap = t;
        }
        out.flush();
        if (json) {
            j.put("ok", trap == null);
            j.put("stdout", captured.toString(StandardCharsets.UTF_8));
            j.put("trap", trap == null ? null : trapJson(src, trap));
            j.put("steps", in.steps());
            line(out, Json.pretty(j));
        } else if (trap != null) {
            err.print(renderTrap(src, trap));
        }
        return trap == null ? 0 : 1;
    }

    private int failCompile(Frontend.Result r, Map<String, Object> j) {
        if (json) {
            j.put("ok", false);
            line(out, Json.pretty(j));
        } else {
            line(err, "check failed: " + r.diags().errorCount() + " error(s)");
        }
        return 1;
    }

    static Map<String, Object> trapJson(Source src, Trap t) {
        return Json.obj("code", t.code.name(), "title", t.code.title, "message", t.getMessage(), "file", src.path,
            "line", t.span.line(), "column", t.span.col(), "source_line", src.line(t.span.line()),
            "trace", t.trace, "hint", t.code.explanation);
    }

    public static String renderTrap(Source src, Trap t) {
        StringBuilder sb = new StringBuilder();
        sb.append("trap[").append(t.code.name()).append("]: ").append(t.getMessage()).append('\n');
        Span s = t.span;
        if (s.line() > 0) {
            String lineNo = String.valueOf(s.line());
            String pad = " ".repeat(lineNo.length());
            String text = src.line(s.line());
            sb.append(pad).append("--> ").append(src.path).append(':').append(s.line()).append(':').append(s.col()).append('\n');
            sb.append(pad).append(" |\n");
            sb.append(lineNo).append(" | ").append(text).append('\n');
            int len = Math.max(1, Math.min(s.length(), Math.max(1, text.length() - s.col() + 1)));
            sb.append(pad).append(" | ").append(" ".repeat(Math.max(0, s.col() - 1))).append("^".repeat(len)).append('\n');
        }
        for (String frame : t.trace) sb.append("  in ").append(frame).append('\n');
        sb.append("  = hint: ").append(t.code.explanation).append('\n');
        return sb.toString();
    }

    // =================================================================== test

    private int test(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("ok", false);
        if (!report(r, j)) return failCompile(r, j);
        List<Object> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (TestDecl t : r.program().itemsOf(TestDecl.class)) {
            if (filter != null && !t.name.contains(filter)) continue;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Interpreter in = new Interpreter(src, captured, maxSteps);
            Trap trap = null;
            try {
                in.runTest(t);
            } catch (Trap e) {
                trap = e;
            }
            String stdout = captured.toString(StandardCharsets.UTF_8);
            if (trap == null) passed++;
            else failed++;
            if (json) {
                results.add(Json.obj("name", t.name, "ok", trap == null, "line", t.span.line(), "stdout", stdout,
                    "trap", trap == null ? null : trapJson(src, trap)));
            } else {
                line(out, "test \"" + t.name + "\" ... " + (trap == null ? "ok" : "FAILED"));
                if (trap != null) {
                    out.flush();
                    if (!stdout.isEmpty()) {
                        line(err, "  output:");
                        for (String line : stdout.split("\n", -1)) {
                            if (!line.isEmpty()) line(err, "    " + line);
                        }
                    }
                    err.print(renderTrap(src, trap).indent(2));
                }
            }
        }
        if (json) {
            j.put("ok", failed == 0);
            j.put("passed", passed);
            j.put("failed", failed);
            j.put("tests", results);
            line(out, Json.pretty(j));
        } else {
            line(out, "");
            line(out, (passed + failed) + " test(s): " + passed + " passed, " + failed + " failed");
        }
        return failed == 0 ? 0 : 1;
    }

    // =================================================================== tools

    private int tools(Source src) {
        Frontend.Result r = compile(src);
        if (!r.ok()) {
            err.print(r.diags().renderText());
            line(err, "check failed: " + r.diags().errorCount() + " error(s)");
            return 1;
        }
        List<Object> list = new ArrayList<>();
        for (FnDecl f : r.program().itemsOf(FnDecl.class)) {
            if (!f.tool) continue;
            list.add(toolSchema(f));
        }
        line(out, Json.pretty(list));
        return 0;
    }

    static Map<String, Object> toolSchema(FnDecl f) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (LocalVar p : f.paramVars) {
            props.put(p.name, JsonValues.schema(p.type));
            required.add(p.name);
        }
        String desc = f.doc.isEmpty() ? "Calls the ALang function " + f.name + "." : f.doc;
        if (!(f.ret instanceof Type.Void)) desc += "\nReturns " + f.ret + ".";
        return Json.obj("name", f.name, "description", desc,
            "input_schema", Json.obj("type", "object", "properties", props, "required", required,
                "additionalProperties", false));
    }

    private int call(Source src) {
        if (positional.size() < 2) throw new UsageException("usage: alang call <file.al> <tool> [json-arguments]");
        String name = positional.get(1);
        String argText;
        boolean fromArgv = positional.size() >= 3 && !positional.get(2).equals("-");
        if (fromArgv) {
            argText = positional.get(2);
        } else {
            try {
                argText = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UsageException("cannot read stdin: " + e.getMessage());
            }
            if (argText.isBlank()) argText = "{}";
        }
        Frontend.Result r = compile(src);
        Map<String, Object> j = new LinkedHashMap<>();
        if (!r.ok()) {
            j.put("ok", false);
            j.put("error", Json.obj("code", "E", "message", "the program has compile errors"));
            j.put("diagnostics", r.diags().toJson());
            line(out, Json.write(j));
            return 1;
        }
        FnDecl f = r.program().function(name);
        if (f == null || !f.tool) {
            List<String> tools = new ArrayList<>();
            for (FnDecl fd : r.program().itemsOf(FnDecl.class)) {
                if (fd.tool) tools.add(fd.name);
            }
            String msg = f == null ? "no function `" + name + "`" : "`" + name + "` is not declared as `tool fn`";
            return callError(j, "bad_tool", msg + "; available tools: " + tools);
        }
        if (fromArgv && argText.indexOf('\uFFFD') >= 0) {
            // Java decodes command-line arguments with the host locale and replaces bytes it cannot
            // decode with U+FFFD, so the text may not be what the caller sent. Refuse it on every host
            // rather than compute a result that depends on the locale; stdin is always read as UTF-8.
            return callError(j, "bad_arguments", "the JSON argument contains U+FFFD, the character Java substitutes for "
                + "bytes it cannot decode in the host locale; pass the arguments on stdin, or write the character as \\ufffd");
        }
        Object parsed;
        try {
            parsed = Json.parse(argText);
        } catch (Json.ParseException e) {
            return callError(j, "bad_json", "arguments are not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> m)) return callError(j, "bad_arguments", "arguments must be a JSON object");
        Object[] args = new Object[f.params.size()];
        try {
            for (Object key : m.keySet()) {
                boolean known = f.paramVars.stream().anyMatch(p -> p.name.equals(key));
                if (!known) throw new JsonValues.DecodeException(key + ": unknown argument");
            }
            for (int i = 0; i < args.length; i++) {
                LocalVar p = f.paramVars.get(i);
                if (!m.containsKey(p.name)) throw new JsonValues.DecodeException(p.name + ": missing argument");
                args[i] = JsonValues.fromJson(m.get(p.name), p.type, p.name);
            }
        } catch (JsonValues.DecodeException e) {
            return callError(j, "bad_arguments", e.getMessage());
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Interpreter in = new Interpreter(src, captured, maxSteps);
        try {
            Object result = in.call(f, args, f.nameSpan);
            j.put("ok", true);
            j.put("result", f.ret instanceof Type.Void ? null : JsonValues.toJson(result, f.ret));
            j.put("stdout", captured.toString(StandardCharsets.UTF_8));
            line(out, Json.write(j));
            return 0;
        } catch (Trap t) {
            j.put("ok", false);
            j.put("error", trapJson(src, t));
            j.put("stdout", captured.toString(StandardCharsets.UTF_8));
            line(out, Json.write(j));
            return 1;
        }
    }

    private int callError(Map<String, Object> j, String code, String message) {
        j.put("ok", false);
        j.put("error", Json.obj("code", code, "message", message));
        line(out, Json.write(j));
        return 1;
    }

    // =================================================================== build

    private int build(Source src) {
        Frontend.Result r = compile(src);
        if (!r.ok()) {
            err.print(r.diags().renderText());
            line(err, "check failed: " + r.diags().errorCount() + " error(s)");
            return 1;
        }
        if (r.program().function("main") == null) {
            line(err, "alang: " + src.path + " has no `fn main()`; the C backend builds executables");
            return 1;
        }
        String c = CGen.generate(r.program());
        if (output == null) {
            out.print(c);
            return 0;
        }
        try {
            Files.writeString(Path.of(output), c, StandardCharsets.UTF_8);
        } catch (IOException e) {
            line(err, "alang: cannot write " + output + ": " + e.getMessage());
            return 1;
        }
        line(out, "wrote " + output + " (compile with: cc -std=c99 -O2 " + output + " -lm)");
        return 0;
    }

    // =================================================================== explain

    private int explain() {
        if (positional.isEmpty()) throw new UsageException("usage: alang explain <CODE> | --all");
        String what = positional.get(0);
        if (what.equals("--markdown") || what.equals("markdown")) {
            out.print(errorsMarkdown());
            return 0;
        }
        if (what.equals("all") || what.equals("--all")) {
            for (Code c : Code.values()) line(out, explainText(c));
            line(out, "Builtin functions:");
            for (Builtin b : Builtin.values()) line(out, "  " + b.signature + "  -- " + b.doc);
            return 0;
        }
        try {
            line(out, explainText(Code.valueOf(what.toUpperCase(Locale.ROOT))));
            return 0;
        } catch (IllegalArgumentException e) {
            line(err, "alang: unknown code " + what + "; codes look like E0201, W0301 or R0003");
            return 2;
        }
    }

    /** Writes a line ending in '\n' on every platform, unlike PrintStream's OS-dependent newline. */
    private static void line(PrintStream p, String s) {
        p.print(s);
        p.print('\n');
    }

    /** The diagnostics catalog as Markdown; docs/ERRORS.md is generated from this. */
    public static String errorsMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ALang diagnostics and traps\n\n");
        sb.append("<!-- Generated by `alang explain markdown`. Do not edit by hand. -->\n\n");
        sb.append("Every message ALang produces has a stable code. `alang explain <CODE>` prints the entry below.\n");
        sb.append("Codes are never reused for a different meaning.\n\n");
        sb.append("- `E....` compile errors (`alang check` fails)\n");
        sb.append("- `W....` warnings (reported, but the program still runs)\n");
        sb.append("- `R....` runtime traps (the program stops; the same trap happens on every run)\n\n");
        sb.append("| Code | Title |\n|------|-------|\n");
        for (Code c : Code.values()) sb.append("| [").append(c.name()).append("](#").append(c.name().toLowerCase(Locale.ROOT))
            .append(") | ").append(c.title).append(" |\n");
        for (Code c : Code.values()) {
            sb.append("\n## ").append(c.name()).append("\n\n**").append(c.title).append("**\n\n");
            sb.append(c.explanation).append("\n\n```\n").append(c.example).append("\n```\n");
        }
        sb.append("\n## Builtin functions\n\n| Signature | Description |\n|-----------|-------------|\n");
        for (Builtin b : Builtin.values()) sb.append("| `").append(b.signature).append("` | ").append(b.doc).append(" |\n");
        return sb.toString();
    }

    static String explainText(Code c) {
        StringBuilder sb = new StringBuilder();
        sb.append(c.name()).append(": ").append(c.title).append('\n');
        sb.append(c.explanation).append('\n');
        sb.append("example:\n");
        for (String line : c.example.split("\n")) sb.append("    ").append(line).append('\n');
        return sb.toString();
    }
}
