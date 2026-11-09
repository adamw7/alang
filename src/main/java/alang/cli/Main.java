package alang.cli;

import alang.ast.Ast.FnDecl;
import alang.ast.Ast.LocalVar;
import alang.ast.Ast.Program;
import alang.ast.Ast.TestDecl;
import alang.ast.Builtin;
import alang.cgen.CGen;
import alang.check.Checker;
import alang.check.MemoryReport;
import alang.diag.Code;
import alang.diag.Source;
import alang.diag.Span;
import alang.interp.Interpreter;
import alang.interp.JsonValues;
import alang.interp.Trap;
import alang.types.Type;
import alang.util.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The `alang` command line tool. */
public final class Main {
    public static final String VERSION = "0.1.0";

    /** How much of a program's output `call`, `run --json` and `test` keep: 1 MiB. */
    static final int MAX_CAPTURED_OUTPUT = 1 << 20;

    private static final String USAGE = """
        ALang %s - an agent-first, heap-free programming language

        usage: alang <command> [options]

        commands:
          check <file.al>              type-check; report diagnostics and the static memory bound
          run <file.al>                check, then run fn main()
          test <file.al>               check, then run every `test "..." { }` block
          tools <file.al>              print JSON schemas of all `tool fn` functions
          call <file.al> <tool> [json] call a tool with a JSON object of arguments (`-`: read it from
                                       stdin; omitted: {}, for a tool without parameters)
          build <file.al> [-o out.c]   compile to heap-free C99 (no malloc; stack and static data only)
          explain <CODE>               explain a diagnostic or trap code, e.g. E0201 (or --all, markdown)
          grammar                      print the path of the ANTLR grammar, grammar/ALang.g4
          version                      print the version

        options (an option a command does not take is a usage error):
          --json             machine-readable output (check, run, test, tools; call always prints JSON)
          --max-steps N      interpreter statement budget (run, test, call; default %d, 0 = unlimited)
          --max-stack BYTES  memory budget: the worst-case stack of each entry point, and the
                             constants (check, run, test, tools, call, build; default %d = 8 MiB,
                             0 = unlimited)
          --filter TEXT      only run tests whose name contains TEXT (test)
          --memory           print the per-function memory table (check)
          -o FILE            output file (build)

        exit codes: 0 success, 1 errors / traps / failed tests, 2 usage error
        """;

    /**
     * The options each command takes. Any other option is a usage error: ignoring it would hide the
     * mistake, e.g. `check -o out.c` writing no file.
     */
    private static final Map<String, Set<String>> OPTIONS = Map.of(
        "check", Set.of("--json", "--memory", "--max-stack"),
        "run", Set.of("--json", "--max-steps", "--max-stack"),
        "test", Set.of("--json", "--filter", "--max-steps", "--max-stack"),
        "tools", Set.of("--json", "--max-stack"),
        "call", Set.of("--json", "--max-steps", "--max-stack"),
        "build", Set.of("-o", "--max-stack"),
        "explain", Set.of(),
        "grammar", Set.of(),
        "version", Set.of(),
        "--version", Set.of());

    /** The options that take a value, the next argument. */
    private static final Set<String> VALUE_OPTIONS = Set.of("--max-steps", "--max-stack", "--filter", "-o");

    private static final Set<String> KNOWN_OPTIONS = Set.of("--json", "--memory", "--max-steps", "--max-stack", "--filter", "-o");

    private final PrintStream out;
    private final PrintStream err;

    private boolean json;
    private boolean memoryTable;
    private long maxSteps = Interpreter.DEFAULT_MAX_STEPS;
    private long maxStack = Checker.DEFAULT_MAX_STACK;
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
            out.print(String.format(Locale.ROOT, USAGE, VERSION, Interpreter.DEFAULT_MAX_STEPS, Checker.DEFAULT_MAX_STACK));
            return args.length == 0 ? 2 : 0;
        }
        String cmd = args[0];
        Set<String> accepted = OPTIONS.get(cmd);
        // `call` reports every error as JSON; check, run, test and tools do when asked to with --json.
        boolean jsonErrors = cmd.equals("call") || (accepted != null && accepted.contains("--json") && jsonRequested(args));
        try {
            if (accepted == null) throw new UsageException("unknown command `" + cmd + "`; run `alang help`");
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (cmd.equals("explain") && (a.equals("--all") || a.equals("--markdown"))) {
                    // The forms the usage shows; explain() also takes them without the dashes.
                    positional.add(a);
                } else if (a.length() > 1 && a.startsWith("-")) {
                    if (!accepted.contains(a)) {
                        throw new UsageException(KNOWN_OPTIONS.contains(a)
                            ? a + " does not apply to `alang " + cmd + "`; see `alang help`"
                            : "unknown option " + a + "; see `alang help`");
                    }
                    switch (a) {
                        case "--json" -> json = true;
                        case "--memory" -> memoryTable = true;
                        case "--max-steps" -> maxSteps = nonNegative(next(args, ++i, a), a);
                        case "--max-stack" -> maxStack = nonNegative(next(args, ++i, a), a);
                        case "--filter" -> filter = next(args, ++i, a);
                        case "-o" -> output = next(args, ++i, a);
                        default -> throw new IllegalStateException(a);
                    }
                } else {
                    positional.add(a);
                }
            }
            // Arguments past these were dropped without a word.
            int most = maxPositional(cmd);
            if (positional.size() > most) {
                throw new UsageException("unexpected argument `" + positional.get(most) + "` for `alang " + cmd + "`; see `alang help`");
            }
            // A changed file name or filter would quietly open another file or match no test. The JSON
            // argument of `call` is checked by call() itself, which reports it as a JSON error.
            for (int i = 0; i < positional.size(); i++) {
                if (!(cmd.equals("call") && i == 2)) requireDecodable(positional.get(i));
            }
            if (filter != null) requireDecodable(filter);
            if (output != null) requireDecodable(output);
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
                default -> throw new IllegalStateException(cmd);
            };
        } catch (UsageException e) {
            return usageError(cmd, jsonErrors, e.getMessage());
        } catch (NumberFormatException e) {
            return usageError(cmd, jsonErrors, "expected a number: " + e.getMessage());
        }
    }

    /** The most positional arguments a command takes: the file, and for `call` the tool and its arguments. */
    private static int maxPositional(String cmd) {
        return switch (cmd) {
            case "call" -> 3;
            case "grammar", "version", "--version" -> 0;
            default -> 1;
        };
    }

    /**
     * Whether the arguments ask for --json. They are scanned before they are parsed, so that a usage error
     * found before --json is reached is reported as JSON too. An option's value is skipped: in
     * `--filter --json`, --json is the filter.
     */
    private static boolean jsonRequested(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--json")) return true;
            if (VALUE_OPTIONS.contains(args[i])) i++;
        }
        return false;
    }

    /**
     * Reports a usage error and returns exit code 2. Where the output is JSON it stays JSON, on stdout:
     * `call`'s error object with code "usage_error", or {"ok": false, "error": message} for the others.
     */
    private int usageError(String cmd, boolean asJson, String message) {
        if (!asJson) {
            line(err, "alang: " + message);
        } else if (cmd.equals("call")) {
            line(out, Json.write(Json.obj("ok", false, "error", Json.obj("code", "usage_error", "message", message))));
        } else {
            line(out, Json.pretty(Json.obj("ok", false, "error", message)));
        }
        return 2;
    }

    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String m) {
            super(m);
        }
    }

    /**
     * True if Java changed the argument while decoding it with the host locale's charset: it replaces the
     * bytes it cannot decode (in the POSIX locale, every non-ASCII byte) with U+FFFD, so the text is not
     * what was typed. Checking for U+FFFD needs no system property, so the result stays the same everywhere.
     */
    private static boolean undecodable(String arg) {
        return arg.indexOf('\uFFFD') >= 0;
    }

    private static void requireDecodable(String arg) {
        if (undecodable(arg)) {
            throw new UsageException("the argument `" + arg + "` could not be decoded in this host locale; "
                + "use a UTF-8 locale such as C.UTF-8 (bin/alang does)");
        }
    }

    /** A limit: a negative one used to mean "no limit" without saying so. */
    private static long nonNegative(String value, String opt) {
        long n = Long.parseLong(value);
        if (n < 0) throw new UsageException(opt + " must be 0 (no limit) or more, found " + value);
        return n;
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
        CappedOutput captured = json ? new CappedOutput(MAX_CAPTURED_OUTPUT) : null;
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
            putStdout(j, captured);
            j.put("trap", trap == null ? null : trapJson(src, trap));
            j.put("steps", in.steps());
            line(out, Json.pretty(j));
        } else if (trap != null) {
            err.writeBytes(renderTrapBytes(src, trap));
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

    /** The trap as text; a byte of a str value in the message that is not UTF-8 becomes U+FFFD. */
    public static String renderTrap(Source src, Trap t) {
        return new String(renderTrapBytes(src, t), StandardCharsets.UTF_8);
    }

    /**
     * The trap as it goes to stderr, byte for byte: the message keeps the bytes of the str values in it,
     * as the C backend writes them, even where they are not UTF-8.
     */
    public static byte[] renderTrapBytes(Source src, Trap t) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.writeBytes(("trap[" + t.code.name() + "]: ").getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(t.messageBytes());
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
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
        bytes.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    // =================================================================== test

    private int test(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("ok", false);
        if (!report(r, j)) return failCompile(r, j);
        List<TestDecl> selected = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (TestDecl t : r.program().itemsOf(TestDecl.class)) {
            names.add("\"" + t.name + "\"");
            if (filter == null || t.name.contains(filter)) selected.add(t);
        }
        if (filter != null && selected.isEmpty()) {
            // A filter with a typo used to run no test and pass.
            String msg = "no test name contains \"" + filter + "\"; "
                + (names.isEmpty() ? src.path + " has no tests" : "the tests are " + String.join(", ", names));
            if (json) {
                j.put("error", msg);
                j.put("passed", 0);
                j.put("failed", 0);
                j.put("tests", List.of());
                line(out, Json.pretty(j));
            } else {
                line(err, "alang: " + msg);
            }
            return 1;
        }
        List<Object> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        for (TestDecl t : selected) {
            CappedOutput captured = new CappedOutput(MAX_CAPTURED_OUTPUT);
            Interpreter in = new Interpreter(src, captured, maxSteps);
            Trap trap = null;
            try {
                in.runTest(t);
            } catch (Trap e) {
                trap = e;
            }
            String stdout = captured.text();
            if (trap == null) passed++;
            else failed++;
            if (json) {
                Map<String, Object> one = Json.obj("name", t.name, "ok", trap == null, "line", t.span.line());
                putStdout(one, captured);
                one.put("trap", trap == null ? null : trapJson(src, trap));
                results.add(one);
            } else {
                line(out, "test \"" + t.name + "\" ... " + (trap == null ? "ok" : "FAILED"));
                if (trap != null) {
                    out.flush();
                    if (!stdout.isEmpty()) {
                        line(err, "  output:");
                        // Every line, blank ones included; only the newline that ends the output starts no line.
                        String body = stdout.endsWith("\n") ? stdout.substring(0, stdout.length() - 1) : stdout;
                        for (String line : body.split("\n", -1)) {
                            line(err, line.isEmpty() ? "" : "    " + line);
                        }
                        if (captured.truncated()) line(err, "    " + truncationNote(captured));
                    }
                    // Indented line by line; ISO-8859-1 maps each byte to one char and back, so the bytes of
                    // the message survive even where they are not UTF-8.
                    err.writeBytes(new String(renderTrapBytes(src, trap), StandardCharsets.ISO_8859_1).indent(2)
                        .getBytes(StandardCharsets.ISO_8859_1));
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
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("ok", false);
        if (!report(r, j)) return failCompile(r, j);
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
        // Arguments come from argv, or from stdin only when asked for with `-`: reading stdin by default
        // made a call without arguments wait forever whenever the caller left stdin open.
        String given = positional.size() >= 3 ? positional.get(2) : null;
        boolean fromArgv = given != null && !given.equals("-");
        String argText;
        if (given == null) {
            argText = "{}";
        } else if (fromArgv) {
            argText = given;
        } else {
            argText = readStdinUtf8();
            if (argText != null && argText.isBlank()) argText = "{}";
        }
        Frontend.Result r = compile(src);
        Map<String, Object> j = new LinkedHashMap<>();
        if (!r.ok()) {
            j.put("ok", false);
            j.put("error", Json.obj("code", "compile_error", "message", "the program has compile errors"));
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
        if (fromArgv && undecodable(argText)) {
            // Refuse rather than silently computing a locale-dependent result.
            return callError(j, "bad_arguments", "the JSON argument contains characters that could not be decoded in this "
                + "host locale; pass the arguments on stdin with `-`, or use a UTF-8 locale such as C.UTF-8");
        }
        if (argText == null) {
            // A lenient decoder would have replaced the bad bytes with U+FFFD and called the tool anyway.
            return callError(j, "bad_arguments", "the JSON arguments on stdin are not valid UTF-8");
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
        CappedOutput captured = new CappedOutput(MAX_CAPTURED_OUTPUT);
        Interpreter in = new Interpreter(src, captured, maxSteps);
        Object result;
        try {
            result = in.call(f, args, f.nameSpan);
        } catch (Trap t) {
            j.put("ok", false);
            j.put("error", trapJson(src, t));
            putStdout(j, captured);
            line(out, Json.write(j));
            return 1;
        }
        Object encoded;
        try {
            encoded = f.ret instanceof Type.Void ? null : JsonValues.toJson(result, f.ret);
        } catch (JsonValues.EncodeException e) {
            j.put("ok", false);
            j.put("error", Json.obj("code", "bad_result", "message", e.messageAt("result")
                + "; JSON has no NaN or infinity, so check for them in the tool and return an enum instead"));
            putStdout(j, captured);
            line(out, Json.write(j));
            return 1;
        }
        j.put("ok", true);
        j.put("result", encoded);
        putStdout(j, captured);
        line(out, Json.write(j));
        return 0;
    }

    /**
     * The captured output; when it was longer than {@link #MAX_CAPTURED_OUTPUT}, also "stdout_truncated":
     * true and "stdout_bytes", the length of the whole output.
     */
    private static void putStdout(Map<String, Object> j, CappedOutput captured) {
        j.put("stdout", captured.text());
        if (captured.truncated()) {
            j.put("stdout_truncated", true);
            j.put("stdout_bytes", captured.total());
        }
    }

    private static String truncationNote(CappedOutput captured) {
        return "output truncated: the program printed " + captured.total() + " bytes, only the first "
            + MAX_CAPTURED_OUTPUT + " are kept";
    }

    /** All of stdin as UTF-8 text, or null if it is not valid UTF-8. */
    private static String readStdinUtf8() {
        byte[] bytes;
        try {
            bytes = System.in.readAllBytes();
        } catch (IOException e) {
            throw new UsageException("cannot read stdin: " + e.getMessage());
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            return null;
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
