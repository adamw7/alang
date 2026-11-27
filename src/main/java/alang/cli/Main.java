package alang.cli;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.cgen.CGen;
import alang.check.*;
import alang.diag.*;
import alang.interp.*;
import alang.types.Type;
import alang.util.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The `alang` command line tool. */
public final class Main {
    /**
     * The {@code <version>} in pom.xml, the one place to change it: the build copies it into
     * {@code alang/version.txt} (a filtered resource), so it is always the version being built.
     */
    public static final String VERSION = readVersion();

    private static String readVersion() {
        try (InputStream in = Main.class.getResourceAsStream("/alang/version.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** How much of a program's output `call`, `run --json` and `test` keep: 1 MiB. */
    static final int MAX_CAPTURED_OUTPUT = 1 << 20;

    private static final String USAGE = """
        ALang %s - an agent-first, heap-free programming language

        usage: alang <command> [options]

        commands:
          check <file.al>              type-check; report diagnostics and the static memory bound
          run <file.al>                check, then run fn main(); a main(input: str[N]) gets all of stdin
          test <file.al>               check, then run every `test "..." { }` block
          tools <file.al>              print JSON tool definitions of all `tool fn` functions, with
                                       example arguments from the tests' calls
          call <file.al> <tool> [json] call a tool with a JSON object of arguments (`-`: read it from
                                       stdin; omitted: {}, for a tool without parameters)
          fuzz <file.al>               call every `tool fn`, and a fn main that takes input, with arguments
                                       at their limits (empty and full strs and lists of many shapes, the
                                       smallest and largest numbers); report each trap and what hit it
          serve <file.al>              serve the `tool fn` functions to an agent host over MCP: JSON-RPC
                                       messages, one per line, on stdin and stdout, or over HTTP with
                                       --http; --watch serves each new version of the program's files
                                       once it compiles
          build <file.al> [-o out.c]   compile to heap-free C99 (no malloc; stack and static data only)
          fmt <file.al>                print the file laid out the one way (indentation, spaces, blank
                                       lines; its line breaks and comments are kept); --write rewrites it,
                                       --check fails if it is not laid out so
          fix <file.al>                print the file with the fix of every diagnostic that has one (those
                                       marked `= fix:`), checking it again after each round; --write
                                       rewrites it
          debug <file.al> [json]       run fn main under a debugger that reads commands from stdin (`help`
                                       lists them): breakpoints, stepping, the stack and the variables,
                                       also where a trap happens; --test or --call debugs a test or a tool
          deps <file.al>               list the packages the program's alang.json declares, with each one's
                                       version and whether its files match their pinned sha256; --lock
                                       pins each package to its files as they are now
          explain <CODE>               explain a diagnostic or trap code, e.g. E0201 (or --all, markdown)
          grammar                      print the path of the ANTLR grammar, grammar/ALang.g4
          version                      print the version

        options (an option a command does not take is a usage error):
          --json             machine-readable output (check, run, test, tools, fuzz, fix, deps; call always prints JSON)
          --max-steps N      interpreter step budget: statements, and the data they go through (run, test,
                             call, tools, fuzz, serve; default %d, 0 = unlimited)
          --timeout SECONDS  stop a tool call that runs longer, a backstop to the step budget (call, serve;
                             default %d, 0 = unlimited)
          --max-stack BYTES  memory budget: the worst-case stack of each entry point, and the
                             constants (check, run, test, tools, call, fuzz, serve, build, fix; default %d = 8 MiB,
                             0 = unlimited)
          --filter TEXT      only run tests whose name contains TEXT (test)
          --memory           print the per-function memory table (check)
          -o FILE            output file (build)
          --write            rewrite the file in place (fmt, fix)
          --check            report a file that is not formatted, and change nothing (fmt)
          --lock             write the sha256 of each package that is unpinned or changed into
                             alang.json (deps)
          --break LOC        a breakpoint before the first command: a line, file:line or a function (debug;
                             may be given more than once)
          --input FILE       the standard input of a fn main that takes it (debug, whose stdin is commands)
          --test NAME        debug the test whose name is, or contains, NAME (debug)
          --call TOOL        debug a call of tool fn TOOL, with the JSON arguments after the file (debug)
          --max-stops N      once the commands run out, show at most N more stops (debug; default %d)
          --watch            poll the program's files and serve each version that compiles, telling the
                             host when the tools change (serve)
          --workers N        tool calls that run at once (serve; default %d)
          --max-queued N     tool calls that may wait for a worker; more are refused as busy (serve;
                             default %d)
          --http [HOST:]PORT serve MCP's Streamable HTTP transport at http://HOST:PORT/mcp instead of
                             stdio, with GET /health and GET /metrics; logs one JSON line per request
                             and per call on stderr (serve; HOST defaults to 127.0.0.1)
          --token-file FILE  require `Authorization: Bearer <the file's first line>` on every HTTP request
                             but /health; needed to listen on anything but a loopback address (serve)
          --allow-origin O   accept HTTP requests from browser origin O, e.g. https://app.example.com
                             (serve; may be given more than once)
          --tls-cert FILE    serve HTTPS, TLS 1.3 only, at https://HOST:PORT/mcp with the PEM certificate
                             chain in FILE, the server's certificate first (serve; with --tls-key)
          --tls-key FILE     the certificate's private key: PEM, unencrypted PKCS#8 (serve; with --tls-cert)
          --hosts FILE       the commands that answer the program's `host fn` calls, as JSON: {"price":
                             {"command": ["python3", "price.py"], "timeout": 5}} ("*" for any other); each
                             call runs one with {"fn": ..., "args": {...}} on stdin and reads its JSON result
                             from stdout (run, call, serve, debug)
          --replay FILE      answer `host fn` calls from a recording: the "host_calls" that `call` and
                             `run --json` report, in the same order (run, call, debug)

        exit codes: 0 success, 1 errors / traps / failed tests, 2 usage error
        """;

    /**
     * The options each command takes. Any other option is a usage error: ignoring it would hide the
     * mistake, e.g. `check -o out.c` writing no file.
     */
    private static final Map<String, Set<String>> OPTIONS = Map.ofEntries(
        Map.entry("check", Set.of("--json", "--memory", "--max-stack")),
        Map.entry("run", Set.of("--json", "--max-steps", "--max-stack", "--hosts", "--replay")),
        Map.entry("test", Set.of("--json", "--filter", "--max-steps", "--max-stack")),
        Map.entry("tools", Set.of("--json", "--max-steps", "--max-stack")),
        Map.entry("call", Set.of("--json", "--max-steps", "--max-stack", "--timeout", "--hosts", "--replay")),
        Map.entry("fuzz", Set.of("--json", "--max-steps", "--max-stack")),
        Map.entry("serve", Set.of("--max-steps", "--max-stack", "--timeout", "--watch", "--workers", "--max-queued",
            "--http", "--token-file", "--allow-origin", "--tls-cert", "--tls-key", "--hosts")),
        Map.entry("build", Set.of("-o", "--max-stack")),
        Map.entry("fmt", Set.of("--write", "--check")),
        Map.entry("fix", Set.of("--write", "--json", "--max-stack")),
        Map.entry("debug", Set.of("--json", "--max-steps", "--max-stack", "--break", "--input", "--test", "--call",
            "--max-stops", "--hosts", "--replay")),
        Map.entry("deps", Set.of("--json", "--lock")),
        Map.entry("explain", Set.of()),
        Map.entry("grammar", Set.of()),
        Map.entry("version", Set.of()),
        Map.entry("--version", Set.of()));

    /** The options that take a value, the next argument. */
    private static final Set<String> VALUE_OPTIONS = Set.of("--max-steps", "--max-stack", "--timeout", "--filter", "-o",
        "--break", "--input", "--test", "--call", "--max-stops", "--workers", "--max-queued", "--http", "--token-file",
        "--allow-origin", "--tls-cert", "--tls-key", "--hosts", "--replay");

    private static final Set<String> KNOWN_OPTIONS = Set.of("--json", "--memory", "--max-steps", "--max-stack", "--timeout",
        "--filter", "-o", "--write", "--check", "--lock", "--watch", "--break", "--input", "--test", "--call", "--max-stops",
        "--workers", "--max-queued", "--http", "--token-file", "--allow-origin", "--tls-cert", "--tls-key", "--hosts",
        "--replay");

    private final PrintStream out;
    private final PrintStream err;
    private final InputStream in;

    private boolean json;
    private boolean memoryTable;
    private boolean write;
    private boolean checkOnly;
    private boolean lock;
    private boolean watch;
    private long maxSteps = Interpreter.DEFAULT_MAX_STEPS;
    private long maxStack = Checker.DEFAULT_MAX_STACK;
    private long timeoutMillis = Watchdog.DEFAULT_MILLIS;
    private String filter;
    private String output;
    private final List<String> breaks = new ArrayList<>();
    private String inputFile;
    private String debugTest;
    private String debugTool;
    private int maxStops = DEFAULT_MAX_STOPS;
    private int workers = McpServer.WORKERS;
    private int maxQueued = McpServer.MAX_QUEUED;
    private String http;
    private String tokenFile;
    private String tlsCert;
    private String tlsKey;
    private String hostsFile;
    private String replayFile;
    private final List<String> allowOrigins = new ArrayList<>();

    /** How many stops `alang debug` shows once its commands have run out. */
    static final int DEFAULT_MAX_STOPS = 1000;
    private final List<String> positional = new ArrayList<>();

    Main(PrintStream out, PrintStream err) {
        this(out, err, System.in);
    }

    Main(PrintStream out, PrintStream err, InputStream in) {
        this.out = out;
        this.err = err;
        this.in = in;
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
            out.print(String.format(Locale.ROOT, USAGE, VERSION, Interpreter.DEFAULT_MAX_STEPS, Watchdog.DEFAULT_MILLIS / 1000,
                Checker.DEFAULT_MAX_STACK, DEFAULT_MAX_STOPS, McpServer.WORKERS, McpServer.MAX_QUEUED));
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
                        case "--write" -> write = true;
                        case "--check" -> checkOnly = true;
                        case "--lock" -> lock = true;
                        case "--watch" -> watch = true;
                        case "--max-steps" -> maxSteps = nonNegative(next(args, ++i, a), a);
                        case "--max-stack" -> maxStack = nonNegative(next(args, ++i, a), a);
                        case "--timeout" -> timeoutMillis = millis(next(args, ++i, a), a);
                        case "--filter" -> filter = next(args, ++i, a);
                        case "-o" -> output = next(args, ++i, a);
                        case "--break" -> breaks.add(next(args, ++i, a));
                        case "--input" -> inputFile = next(args, ++i, a);
                        case "--test" -> debugTest = next(args, ++i, a);
                        case "--call" -> debugTool = next(args, ++i, a);
                        case "--max-stops" -> maxStops = (int) Math.min(Integer.MAX_VALUE, nonNegative(next(args, ++i, a), a));
                        case "--workers" -> workers = positiveInt(next(args, ++i, a), a);
                        case "--max-queued" -> maxQueued = positiveInt(next(args, ++i, a), a);
                        case "--http" -> http = next(args, ++i, a);
                        case "--token-file" -> tokenFile = next(args, ++i, a);
                        case "--allow-origin" -> allowOrigins.add(next(args, ++i, a));
                        case "--tls-cert" -> tlsCert = next(args, ++i, a);
                        case "--tls-key" -> tlsKey = next(args, ++i, a);
                        case "--hosts" -> hostsFile = next(args, ++i, a);
                        case "--replay" -> replayFile = next(args, ++i, a);
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
                if (!(cmd.equals("call") && i == 2) && !(cmd.equals("debug") && i == 1)) requireDecodable(positional.get(i));
            }
            if (filter != null) requireDecodable(filter);
            if (output != null) requireDecodable(output);
            return switch (cmd) {
                case "check" -> check(file());
                case "run" -> runMain(file());
                case "test" -> test(file());
                case "tools" -> tools(file());
                case "call" -> call(file());
                case "fuzz" -> fuzz(file());
                case "serve" -> serve(file());
                case "build" -> build(file());
                case "fmt" -> fmt(file());
                case "fix" -> fix(file());
                case "debug" -> debug(file());
                case "deps" -> deps();
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
            case "debug" -> 2;
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
            line(out, Json.write(Json.obj("ok", false, "error", message)));
        }
        return 2;
    }

    static final class UsageException extends RuntimeException {
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

    private static int positiveInt(String value, String opt) {
        long n = Long.parseLong(value);
        if (n < 1 || n > 100_000) throw new UsageException(opt + " must be from 1 to 100000, found " + value);
        return (int) n;
    }

    /** Seconds, such as 60 or 0.5, as whole milliseconds (rounded up, so that a limit is never 0 by accident). */
    private static long millis(String value, String opt) {
        BigDecimal seconds = new BigDecimal(value);
        if (seconds.signum() < 0) throw new UsageException(opt + " must be 0 (no limit) or more seconds, found " + value);
        try {
            return seconds.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException e) {
            throw new UsageException(opt + " is too large: " + value + " seconds");
        }
    }

    private static String next(String[] args, int i, String opt) {
        if (i >= args.length) throw new UsageException(opt + " needs a value");
        return args[i];
    }

    private Source file() {
        if (positional.isEmpty()) throw new UsageException("missing source file");
        Path p = Path.of(positional.get(0));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(p);
        } catch (NoSuchFileException e) {
            throw new UsageException("file not found: " + p);
        } catch (IOException e) {
            throw new UsageException("cannot read " + p + ": " + e.getMessage());
        }
        // Files.readString reported a byte that is not UTF-8 only as "Input length = 1".
        String text = Utf8.decode(bytes);
        if (text == null) {
            int at = Utf8.firstBad(bytes, bytes.length);
            throw new UsageException(p + " is not UTF-8 text (byte 0x" + String.format(Locale.ROOT, "%02x", bytes[at] & 0xFF)
                + " at offset " + at + "); save it as UTF-8");
        }
        return new Source(p.toString(), text);
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
        Map<String, Object> j = Json.obj("ok", r.ok(), "file", src.path, "errors", r.diags().errorCount(), "warnings",
            r.diags().warningCount());
        report(r, j);
        MemoryReport mem = r.ok() ? r.check().memory() : null;
        if (json) {
            j.put("memory", mem == null ? null : mem.toJson());
            line(out, Json.write(j));
            return r.ok() ? 0 : 1;
        }
        if (!r.ok()) {
            int fixable = r.diags().fixableCount();
            line(err, "check failed: " + r.diags().errorCount() + " error(s), " + r.diags().warningCount() + " warning(s)"
                + (fixable == 0 ? "" : "; `alang fix " + src.path + " --write` fixes the " + fixable + " marked `= fix:`"));
            return 1;
        }
        Program p = r.program();
        int imported = p.files.size() - 1;
        String with = imported == 0 ? "" : " with " + imported + " imported file(s)";
        line(out, "ok: " + src.path + with + ": " + p.itemsOf(FnDecl.class).size() + " function(s), "
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
        Map<String, Object> j = Json.obj("ok", false, "file", src.path);
        if (!report(r, j)) return failCompile(r, j);
        FnDecl main = r.program().function("main");
        if (main == null) {
            String msg = "no `fn main()` in " + src.path + "; use `alang test` for tests or `alang call` for tools";
            return fail(j, msg);
        }
        // A main that takes a str gets the standard input, all of it, as bytes. One byte past its capacity is enough
        // to know the input does not fit, which traps; reading on would hold an input of any size in memory first.
        byte[] input = new byte[0];
        if (!main.paramVars.isEmpty()) {
            try {
                input = in.readNBytes(Interpreter.inputCapacity(main.paramVars.get(0)) + 1);
            } catch (IOException e) {
                throw new UsageException("cannot read stdin: " + e.getMessage());
            }
        }
        CappedOutput captured = json ? new CappedOutput(MAX_CAPTURED_OUTPUT) : null;
        OutputStream sink = json ? captured : out;
        Interpreter interp = new Interpreter(src, sink, maxSteps);
        interp.host(Hosts.load(hostsFile, replayFile).get());
        Trap trap = null;
        try {
            interp.runMain(main, input);
        } catch (Trap t) {
            trap = t;
        }
        out.flush();
        if (json) {
            j.put("ok", trap == null);
            putStdout(j, captured);
            j.put("trap", trap == null ? null : trapJson(src, trap));
            if (!interp.hostCalls().isEmpty()) j.put("host_calls", interp.hostCalls());
            j.put("steps", interp.steps());
            line(out, Json.write(j));
        } else if (trap != null) {
            err.writeBytes(renderTrapBytes(src, trap));
        }
        return trap == null ? 0 : 1;
    }

    private int failCompile(Frontend.Result r, Map<String, Object> j) {
        if (json) {
            j.put("ok", false);
            line(out, Json.write(j));
        } else {
            line(err, "check failed: " + r.diags().errorCount() + " error(s)");
        }
        return 1;
    }

    /**
     * A trap as JSON. Its line and column are in the file given on the command line, which `run` and
     * `test` name once at the top level and `call` leaves out, as the caller passed it; a trap in an
     * imported file has a "file" key, that file's path.
     */
    static Map<String, Object> trapJson(Source src, Trap t) {
        Source file = t.span.file() == null ? src : t.span.file();
        Map<String, Object> j = Json.obj("code", t.code.name(), "title", t.code.title, "message", t.getMessage());
        if (file != src) j.put("file", file.path);
        j.put("line", t.span.line());
        j.put("column", t.span.col());
        j.put("source_line", file.line(t.span.line()));
        j.put("trace", t.trace);
        j.put("hint", t.code.explanation);
        return j;
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
        Diagnostics.excerpt(sb, src, t.span);
        for (String frame : t.trace) sb.append("  in ").append(frame).append('\n');
        sb.append("  = hint: ").append(t.code.explanation).append('\n');
        bytes.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    // =================================================================== test

    private int test(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = Json.obj("ok", false, "file", src.path);
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
                line(out, Json.write(j));
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
            Interpreter interp = new Interpreter(src, captured, maxSteps);
            Trap trap = null;
            try {
                interp.runTest(t);
            } catch (Trap e) {
                trap = e;
            }
            String stdout = captured.text();
            if (trap == null) passed++;
            else failed++;
            if (json) {
                Map<String, Object> one = Json.obj("name", t.name, "ok", trap == null, "line", t.span.line());
                if (t.expectedTrap != null) one.put("traps", t.expectedTrap.name());
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
                        for (String line : body.split("\n", -1)) line(err, line.isEmpty() ? "" : "    " + line);
                        if (captured.truncated()) line(err, "    " + truncationNote(captured));
                    }
                    err.writeBytes(indented(renderTrapBytes(src, trap)));
                }
            }
        }
        if (json) {
            j.put("ok", failed == 0);
            j.put("passed", passed);
            j.put("failed", failed);
            j.put("tests", results);
            line(out, Json.write(j));
        } else {
            line(out, "");
            line(out, (passed + failed) + " test(s): " + passed + " passed, " + failed + " failed");
        }
        return failed == 0 ? 0 : 1;
    }

    /**
     * Each line of {@code text} indented by two spaces, byte for byte, so the bytes of str values in a trap
     * message survive even where they are not UTF-8. Only '\n' ends a line: String.indent also broke lines
     * at a '\r', so panic("a\rb") showed a line break that `alang run` does not.
     */
    static byte[] indented(byte[] text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(text.length + 64);
        boolean lineStart = true;
        for (byte b : text) {
            if (lineStart) {
                out.write(' ');
                out.write(' ');
            }
            out.write(b);
            lineStart = b == '\n';
        }
        return out.toByteArray();
    }

    // =================================================================== tools

    private int tools(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = Json.obj("ok", false, "file", src.path);
        if (!report(r, j)) return failCompile(r, j);
        List<Object> list = new ArrayList<>();
        for (FnDecl f : r.program().itemsOf(FnDecl.class)) {
            if (!f.tool) continue;
            list.add(toolSchema(f, r.program(), src));
        }
        line(out, Json.write(list));
        return 0;
    }

    /**
     * A tool definition in the shape of the Messages API's `tools`, with `input_examples` when the tests
     * call the tool with constant arguments.
     */
    private Map<String, Object> toolSchema(FnDecl f, Program p, Source src) {
        Map<String, Object> tool = Json.obj("name", f.name, "description", ToolDefinitions.description(f),
            "input_schema", ToolDefinitions.inputSchema(f));
        List<Object> examples = ToolExamples.of(f, p, src, maxSteps);
        if (!examples.isEmpty()) tool.put("input_examples", examples);
        return tool;
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
            line(out, Json.write(Json.obj("ok", false, "error", Json.obj("code", "compile_error", "message",
                "the program has compile errors"), "diagnostics", r.diags().toJson())));
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
        Host host = Hosts.load(hostsFile, replayFile).get();
        Meter meter = new Meter(maxSteps);
        Map<String, Object> report;
        try (Watchdog watchdog = new Watchdog(timeoutMillis)) {
            Watchdog.Alarm alarm = watchdog.watch(meter);
            report = callTool(src, f, parsed, meter, host);
            alarm.off();
        }
        line(out, Json.write(report));
        return report.get("ok") == Boolean.TRUE ? 0 : 1;
    }

    // =================================================================== debug

    /** A tool's result, as the debugger's last event shows it: JSON, and the text print() would write. */
    record ToolResult(Object json, String text) {}

    /**
     * `alang debug`: fn main, the test --test names, or the tool --call names, run under {@link Debug}, which reads
     * its commands from stdin. Compile errors and a bad target are reported as `run` reports them.
     */
    private int debug(Source src) {
        if (debugTest != null && debugTool != null) throw new UsageException("--test and --call cannot be used together");
        if (positional.size() > 1 && debugTool == null) {
            throw new UsageException("unexpected argument `" + positional.get(1) + "`: JSON arguments go with --call TOOL");
        }
        Frontend.Result r = compile(src);
        Map<String, Object> j = Json.obj("ok", false, "file", src.path);
        if (!report(r, j)) return failCompile(r, j);
        Program p = r.program();
        Debug.Target target;
        if (debugTest != null) {
            List<TestDecl> found = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (TestDecl t : p.itemsOf(TestDecl.class)) {
                names.add("\"" + t.name + "\"");
                if (t.name.equals(debugTest)) {
                    found.clear();
                    found.add(t);
                    break;
                }
                if (t.name.contains(debugTest)) found.add(t);
            }
            if (found.size() != 1) {
                List<String> hits = new ArrayList<>();
                for (TestDecl t : found) hits.add("\"" + t.name + "\"");
                return fail(j, found.isEmpty()
                    ? "no test name contains \"" + debugTest + "\"; " + (names.isEmpty() ? src.path + " has no tests"
                        : "the tests are " + String.join(", ", names))
                    : "--test \"" + debugTest + "\" matches " + found.size() + " tests: " + String.join(", ", hits)
                        + "; give one's whole name");
            }
            TestDecl t = found.get(0);
            target = in -> {
                in.runTest(t);
                return null;
            };
        } else if (debugTool != null) {
            FnDecl f = p.function(debugTool);
            if (f == null || !f.tool) {
                List<String> tools = new ArrayList<>();
                for (FnDecl fd : p.itemsOf(FnDecl.class)) {
                    if (fd.tool) tools.add(fd.name);
                }
                return fail(j, (f == null ? "no function `" + debugTool + "`" : "`" + debugTool + "` is not declared as `tool fn`")
                    + "; available tools: " + tools);
            }
            Object[] args;
            try {
                String text = positional.size() > 1 ? positional.get(1) : "{}";
                if (undecodable(text)) {
                    return fail(j, "the JSON argument could not be decoded in this host locale; use a UTF-8 locale such as C.UTF-8");
                }
                args = toolArguments(f, Json.parse(text));
            } catch (Json.ParseException e) {
                return fail(j, "arguments are not valid JSON: " + e.getMessage());
            } catch (JsonValues.DecodeException e) {
                return fail(j, "bad arguments: " + e.getMessage());
            }
            target = in -> {
                Object v = in.call(f, args, f.nameSpan);
                if (f.ret instanceof Type.Void) return null;
                Object jv;
                try {
                    jv = JsonValues.toJson(v, f.ret);
                } catch (JsonValues.EncodeException e) {
                    jv = null;
                }
                return new ToolResult(jv, alang.interp.Format.toText(v, f.ret));
            };
        } else {
            FnDecl main = p.function("main");
            if (main == null) {
                return fail(j, "no `fn main()` in " + src.path + "; debug a test with --test or a tool with --call");
            }
            byte[] input = new byte[0];
            if (!main.paramVars.isEmpty() && inputFile != null) {
                try (InputStream f = Files.newInputStream(Path.of(inputFile))) {
                    input = f.readNBytes(Interpreter.inputCapacity(main.paramVars.get(0)) + 1);
                } catch (NoSuchFileException e) {
                    throw new UsageException("file not found: " + inputFile);
                } catch (IOException e) {
                    throw new UsageException("cannot read " + inputFile + ": " + e.getMessage());
                }
            } else if (inputFile != null) {
                throw new UsageException("--input given, but fn main in " + src.path + " takes no input");
            }
            byte[] given = input;
            target = in -> {
                in.runMain(main, given);
                return null;
            };
        }
        java.io.ByteArrayOutputStream held = json ? new java.io.ByteArrayOutputStream() : null;
        Interpreter interp = new Interpreter(src, json ? held : out, maxSteps);
        interp.host(Hosts.load(hostsFile, replayFile).get());
        java.io.BufferedReader commands = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        boolean interactive = in == System.in && System.console() != null;
        Debug d = new Debug(src, p, commands, out, json, interactive, maxStops, held, r.check().expressions());
        int code = d.session(interp, target, breaks);
        out.flush();
        return code;
    }

    /** Reports {@code msg} as the "error" of {@code j} with --json, or as text on stderr: exit status 1. */
    private int fail(Map<String, Object> j, String msg) {
        if (json) {
            j.put("error", msg);
            line(out, Json.write(j));
        } else {
            line(err, "alang: " + msg);
        }
        return 1;
    }

    /**
     * Calls tool {@code f} with {@code arguments}, parsed JSON that should be an object holding each
     * parameter, and returns the report `alang call` prints: "ok", then "result" or "error", "stdout" and
     * "steps"; arguments that do not fit the parameters give a bad_arguments error, and no call. The call is
     * charged to {@code meter}, which holds its budget, and stops with a "timeout" or "cancelled" error when
     * another thread stops the meter.
     */
    static Map<String, Object> callTool(Source src, FnDecl f, Object arguments, Meter meter, Host host) {
        Object[] args;
        try {
            args = toolArguments(f, arguments);
        } catch (JsonValues.DecodeException e) {
            return badArguments(e.getMessage());
        }
        return runTool(src, f, args, meter, host);
    }

    /**
     * The values tool {@code f} is called with, decoded from {@code arguments}: parsed JSON that should be an
     * object holding each parameter, or leaving out one with a default value. Throws a DecodeException that
     * says what does not fit.
     */
    public static Object[] toolArguments(FnDecl f, Object arguments) {
        if (!(arguments instanceof Map<?, ?> m)) throw new JsonValues.DecodeException("arguments must be a JSON object");
        for (Object key : m.keySet()) {
            boolean known = f.paramVars.stream().anyMatch(p -> p.name.equals(key));
            if (!known) throw new JsonValues.DecodeException(key + ": unknown argument");
        }
        Object[] args = new Object[f.params.size()];
        for (int i = 0; i < args.length; i++) {
            LocalVar p = f.paramVars.get(i);
            if (!m.containsKey(p.name) && f.defaults.get(i) != null) {
                args[i] = Values.copy(f.defaults.get(i).value, new Meter(0));
                continue;
            }
            if (!m.containsKey(p.name) && JsonValues.omittable(p.type)) {
                // An Option left out is Option.None, as null is.
                args[i] = JsonValues.none();
                continue;
            }
            if (!m.containsKey(p.name)) throw new JsonValues.DecodeException(p.name + ": missing argument");
            args[i] = JsonValues.fromJson(m.get(p.name), p.type, p.name);
        }
        return args;
    }

    /** The report of a call whose arguments do not fit its tool, which is therefore not made. */
    public static Map<String, Object> badArguments(String message) {
        return Json.obj("ok", false, "error", Json.obj("code", "bad_arguments", "message", message));
    }

    /**
     * Calls tool {@code f} with {@code args}, from {@link #toolArguments}, and returns the report `alang call`
     * prints, as {@link #callTool} does.
     */
    public static Map<String, Object> runTool(Source src, FnDecl f, Object[] args, Meter meter) {
        return runTool(src, f, args, meter, Host.NONE);
    }

    /**
     * {@link #runTool(Source, FnDecl, Object[], Meter)} with {@code host} answering the calls of host fns; the report
     * has "host_calls", each call it was asked, after "result" or "error" when there were any.
     */
    public static Map<String, Object> runTool(Source src, FnDecl f, Object[] args, Meter meter, Host host) {
        Map<String, Object> j = Json.obj("ok", false);  // set again below, where it keeps its place as the first key
        CappedOutput captured = new CappedOutput(MAX_CAPTURED_OUTPUT);
        Interpreter in = new Interpreter(src, captured, meter);
        in.host(host);
        try {
            Object result = in.call(f, args, f.nameSpan);
            j.put("result", f.ret instanceof Type.Void ? null : JsonValues.toJson(result, f.ret));
        } catch (Trap t) {
            j.put("error", trapJson(src, t));
        } catch (Stopped s) {
            j.put("error", Json.obj("code", s.code, "message", s.getMessage()));
        } catch (JsonValues.EncodeException e) {
            j.put("error", Json.obj("code", "bad_result", "message", e.messageAt("result")));
        }
        j.put("ok", !j.containsKey("error"));
        if (!in.hostCalls().isEmpty()) j.put("host_calls", in.hostCalls());
        putStdout(j, captured);
        // The call's cost: the statements it ran and the data they went through, the same on every run (unless
        // it was stopped by the clock), as `run --json` reports.
        j.put("steps", in.steps());
        return j;
    }

    /**
     * Calls the tools, and a main that takes input, at their limits ({@link Fuzz}). Exit code 1 when a call
     * traps: each distinct trap is reported once, with the first arguments that hit it.
     */
    private int fuzz(Source src) {
        Frontend.Result r = compile(src);
        Map<String, Object> j = Json.obj("ok", false, "file", src.path);
        if (!report(r, j)) return failCompile(r, j);
        List<Fuzz.Entry> entries = Fuzz.run(r.program(), src, maxSteps);
        int calls = 0;
        int traps = 0;
        List<Object> list = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Fuzz.Entry e : entries) {
            calls += e.calls();
            traps += e.findings().size();
            List<Object> found = new ArrayList<>();
            text.append(e.tool() ? "tool " : "fn ").append(e.name()).append(": ").append(e.calls()).append(" call(s), ")
                .append(e.findings().isEmpty() ? "no traps" : e.findings().size() + " trap(s)").append('\n');
            for (Fuzz.Finding f : e.findings()) {
                Map<String, Object> t = trapJson(src, f.trap());
                t.put(e.tool() ? "arguments" : "input", f.input());
                if (!f.hostCalls().isEmpty()) t.put("host_calls", f.hostCalls());
                found.add(t);
                String trap = renderTrap(src, f.trap());
                text.append(trap.endsWith("\n") ? trap : trap + "\n");
                text.append("  = ").append(e.tool() ? "arguments: " : "input: ").append(abbreviated(f.input())).append("\n");
                // The replies the fuzzer gave, the last ones first to go: the trap followed the last.
                List<Object> asked = f.hostCalls();
                int from = Math.max(0, asked.size() - 4);
                if (from > 0) text.append("  = host fns replied ").append(from).append(" time(s) before these:\n");
                for (Object call : asked.subList(from, asked.size())) {
                    Map<?, ?> c = (Map<?, ?>) call;
                    text.append("  = host fn ").append(c.get("fn")).append(" replied: ")
                        .append(abbreviated(c.containsKey("result") ? c.get("result") : "(no reply)")).append("\n");
                }
                text.append("\n");
            }
            list.add(Json.obj("name", e.name(), "kind", e.tool() ? "tool" : "main", "calls", e.calls(), "traps", found));
        }
        if (json) {
            j.put("ok", traps == 0);
            j.put("calls", calls);
            j.put("entries", list);
            line(out, Json.write(j));
        } else if (entries.isEmpty()) {
            line(out, "fuzz: " + src.path + " has no `tool fn` and no fn main(input: str[N]) to call");
        } else {
            out.print(text);
            line(out, "fuzz: " + (traps == 0 ? "no traps" : traps + " trap(s)") + " in " + calls + " call(s)");
        }
        return traps == 0 ? 0 : 1;
    }

    /** JSON with each string of more than 60 characters cut short: "a b c d … (1024 bytes)". */
    private static String abbreviated(Object v) {
        return Json.write(shorten(v));
    }

    private static Object shorten(Object v) {
        if (v instanceof String s && s.length() > 60) {
            return s.substring(0, 48) + "… (" + s.getBytes(StandardCharsets.UTF_8).length + " bytes)";
        }
        if (v instanceof List<?> l && l.size() > 8) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < 6; i++) out.add(shorten(l.get(i)));
            out.add("… (" + l.size() + " elements)");
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(Main::shorten).toList();
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), shorten(e.getValue()));
            return out;
        }
        return v;
    }

    /** How often `serve --watch` looks at the program's files. A change is served once two looks agree. */
    static final long WATCH_MILLIS = 300;

    /**
     * Serves the tools over MCP until stdin ends. Stdout carries only protocol messages, so diagnostics,
     * warnings included, go to stderr, and a program with errors does not start a server at all. With
     * --watch, a later version with errors is reported there too, and the one before it stays served.
     */
    private int serve(Source src) {
        Frontend.Result r = compile(src);
        err.print(r.diags().renderText());
        if (!r.ok()) {
            line(err, "check failed: " + r.diags().errorCount() + " error(s)");
            return 1;
        }
        if (http == null && (tokenFile != null || !allowOrigins.isEmpty() || tlsCert != null || tlsKey != null)) {
            throw new UsageException("--token-file, --allow-origin, --tls-cert and --tls-key go with --http");
        }
        if ((tlsCert == null) != (tlsKey == null)) {
            throw new UsageException("--tls-cert and --tls-key go together: HTTPS needs the certificate and its key");
        }
        HttpTransport.Config config = http == null ? null : httpConfig();
        try (Watchdog watchdog = new Watchdog(timeoutMillis)) {
            McpServer server = new McpServer(src, r.program(), maxSteps, watchdog, workers, maxQueued);
            server.hosts(Hosts.load(hostsFile, null));
            if (watch) server.watch(new Reloader(r.program(), maxStack), WATCH_MILLIS, err);
            if (config == null) {
                server.serve(in, out);
                return 0;
            }
            HttpTransport transport;
            try {
                transport = new HttpTransport(server, config, err);
            } catch (IllegalArgumentException e) {
                line(err, "alang: " + e.getMessage());
                return 2;
            }
            try {
                transport.start();
            } catch (IOException e) {
                line(err, "alang: cannot listen on " + config.host() + ":" + config.port() + ": " + e.getMessage());
                transport.stop();
                return 1;
            }
            transport.stopOnShutdown();
            transport.await();
        } catch (IOException e) {
            line(err, "alang: cannot read stdin: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    /** The address --http names, [HOST:]PORT, and the token of --token-file, read once, at start. */
    private HttpTransport.Config httpConfig() {
        String host = "127.0.0.1";
        String port = http;
        int colon = http.lastIndexOf(':');
        if (colon >= 0) {
            host = http.substring(0, colon);
            port = http.substring(colon + 1);
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            if (host.isEmpty()) throw new UsageException("--http takes [HOST:]PORT, found " + http);
        }
        int p;
        try {
            p = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new UsageException("--http takes [HOST:]PORT, found " + http);
        }
        if (p < 0 || p > 65535) throw new UsageException("--http: the port must be from 0 to 65535, found " + port);
        String token = null;
        if (tokenFile != null) {
            try {
                String text = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8);
                token = text.lines().findFirst().orElse("").strip();
            } catch (IOException | RuntimeException e) {
                throw new UsageException("--token-file: cannot read " + tokenFile + ": " + e.getMessage());
            }
            if (token.length() < 16) throw new UsageException("--token-file: the token must be at least 16 characters");
        }
        return new HttpTransport.Config(host, p, token, List.copyOf(allowOrigins), readOption("--tls-cert", tlsCert),
            readOption("--tls-key", tlsKey));
    }

    /** The text of the file an option names, or null without the option. */
    private static String readOption(String option, String file) {
        if (file == null) return null;
        try {
            // PEM is ASCII; Latin-1 reads any bytes, so a DER file is told it has no PEM block, not that it is not UTF-8.
            return Files.readString(Path.of(file), StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException e) {
            throw new UsageException(option + ": cannot read " + file + ": " + e.getMessage());
        }
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
    private String readStdinUtf8() {
        byte[] bytes;
        try {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UsageException("cannot read stdin: " + e.getMessage());
        }
        return Utf8.decode(bytes);
    }

    private int callError(Map<String, Object> j, String code, String message) {
        j.put("ok", false);
        j.put("error", Json.obj("code", code, "message", message));
        line(out, Json.write(j));
        return 1;
    }

    // =================================================================== build

    /** alang fmt: the file laid out by {@link alang.parse.Formatter}, printed, checked or written back. */
    private int fmt(Source src) {
        if (write && checkOnly) throw new UsageException("--write and --check cannot be used together");
        alang.diag.Diagnostics diags = new alang.diag.Diagnostics(src);
        String formatted = alang.parse.Formatter.format(src, diags);
        if (formatted == null) {
            err.print(diags.renderText());
            line(err, "fmt failed: " + diags.errorCount() + " syntax error(s); nothing was changed");
            return 1;
        }
        boolean same = formatted.equals(src.text);
        if (checkOnly) {
            if (same) return 0;
            line(err, src.path + " is not formatted; run: alang fmt " + src.path + " --write");
            return 1;
        }
        if (!write) {
            out.print(formatted);
            return 0;
        }
        if (same) return 0;
        try {
            Files.writeString(Path.of(positional.get(0)), formatted, StandardCharsets.UTF_8);
        } catch (IOException e) {
            line(err, "alang: cannot write " + src.path + ": " + e.getMessage());
            return 1;
        }
        line(out, "formatted " + src.path);
        return 0;
    }

    /**
     * `alang deps`: the packages of the manifest nearest to the program, each with its directory, version and
     * whether its files match the sha256 pinned for it; with --lock, pins those that are unpinned or changed.
     */
    private int deps() {
        if (positional.size() != 1) throw new UsageException("usage: alang deps <file.al> [--lock] [--json]");
        String main = positional.get(0);
        if (!Files.isRegularFile(Path.of(main))) throw new UsageException("file not found: " + main);
        Packages.Manifest m = Packages.nearest(main, null);
        if (m == null) {
            if (json) {
                line(out, Json.write(Json.obj("ok", true, "manifest", null, "packages", List.of())));
            } else {
                line(out, "no " + Packages.MANIFEST + " beside " + main + " or above it: the program uses no packages");
            }
            return 0;
        }
        if (m.error() != null) {
            String message = "invalid " + m.file() + ": " + m.error();
            if (json) {
                line(out, Json.write(Json.obj("ok", false, "manifest", m.file(), "error", message)));
            } else {
                line(err, "error[E0705]: " + message);
            }
            return 1;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> pins = new LinkedHashMap<>();
        for (Packages.Dependency d : m.deps().values()) {
            String dir = m.dirOf(d);
            if (dir.isEmpty()) dir = ".";
            String version = null;
            String hash = null;
            String status;
            if (!Files.isDirectory(Path.of(dir))) {
                status = "missing";
            } else {
                Packages.Manifest own = Files.isRegularFile(Path.of(dir, Packages.MANIFEST)) ? Packages.read(dir + "/" + Packages.MANIFEST) : null;
                if (own != null) version = own.version();
                try {
                    hash = Packages.hash(dir);
                } catch (IOException | UncheckedIOException e) {
                    status = "unreadable";
                    rows.add(Json.obj("name", d.name(), "path", dir, "version", version, "pinned", d.sha256(), "sha256", null,
                        "status", status));
                    continue;
                }
                status = d.sha256() == null ? "unpinned" : d.sha256().equals(hash) ? "ok" : "changed";
                if (lock && !status.equals("ok")) {
                    pins.put(d.name(), hash);
                    status = "locked";
                }
            }
            rows.add(Json.obj("name", d.name(), "path", dir, "version", version, "pinned", lock && pins.containsKey(d.name()) ? hash
                : d.sha256(), "sha256", hash, "status", status));
        }
        if (!pins.isEmpty()) {
            try {
                Files.writeString(Path.of(m.file()), Packages.withPins(m, pins), StandardCharsets.UTF_8);
            } catch (IOException e) {
                line(err, "alang: cannot write " + m.file() + ": " + e.getMessage());
                return 1;
            }
        }
        boolean ok = rows.stream().allMatch(r -> r.get("status").equals("ok") || r.get("status").equals("locked"));
        if (json) {
            line(out, Json.write(Json.obj("ok", ok, "manifest", m.file(), "packages", rows)));
        } else {
            line(out, m.file() + (m.deps().isEmpty() ? ": no dependencies" : ""));
            for (Map<String, Object> r : rows) {
                String version = r.get("version") == null ? "" : " " + r.get("version");
                String note = switch ((String) r.get("status")) {
                    case "ok" -> "ok";
                    case "locked" -> "pinned to " + r.get("sha256");
                    case "unpinned" -> "not pinned; run: alang deps " + main + " --lock";
                    case "changed" -> "files changed since pinned (sha256 now " + r.get("sha256") + "); restore them, or run: alang deps "
                        + main + " --lock";
                    case "missing" -> "missing: vendor it in " + r.get("path") + "; the toolchain never downloads";
                    default -> "cannot read " + r.get("path");
                };
                line(out, "  " + r.get("name") + version + "  " + r.get("path") + "  " + note);
            }
        }
        return ok ? 0 : 1;
    }

    private int fix(Source src) {
        Fixer.Outcome o = Fixer.run(src, this::compile);
        Frontend.Result r = o.result();
        boolean changed = !o.text().equals(src.text);
        if (write && changed) {
            try {
                Files.writeString(Path.of(positional.get(0)), o.text(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                line(err, "alang: cannot write " + src.path + ": " + e.getMessage());
                return 1;
            }
        }
        if (json) {
            Map<String, Object> j = Json.obj("ok", r.ok(), "file", src.path);
            List<Object> fixed = new ArrayList<>();
            for (Fixer.Fixed f : o.fixed()) {
                Map<String, Object> one = alang.diag.Diagnostics.toJson(src, f.diagnostic());
                one.put("description", f.description());
                fixed.add(one);
            }
            j.put("fixed", fixed);
            j.put("written", write && changed);
            j.put("diagnostics", r.diags().toJson());
            if (!write) j.put("source", o.text());
            line(out, Json.write(j));
            return r.ok() ? 0 : 1;
        }
        if (!write) out.print(o.text());
        PrintStream log = write ? out : err;
        for (Fixer.Fixed f : o.fixed()) {
            alang.diag.Diagnostic d = f.diagnostic();
            line(log, "fixed[" + d.code().name() + "] " + src.path + ":" + d.span().line() + ":" + d.span().col() + ": "
                + f.description());
        }
        if (write) line(out, changed ? "wrote " + src.path + ": " + o.fixed().size() + " fix(es)" : src.path + ": nothing to fix");
        err.print(r.diags().renderText());
        if (!r.ok()) {
            line(err, "fix: " + r.diags().errorCount() + " error(s) remain" + (r.diags().fixableCount() > 0 ? "" : " without a mechanical fix")
                + "; their hints say what to change");
            return 1;
        }
        return 0;
    }

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
            line(out, "Builtin functions and the standard library:");
            for (Builtin.Group g : Builtin.Group.values()) {
                line(out, "  " + g.title + ":");
                for (Builtin b : Builtin.values()) {
                    if (b.group == g) line(out, "    " + b.signature + "  -- " + b.doc);
                }
            }
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
            sb.append("\n## ").append(c.name()).append("\n\n**").append(c.title).append("**: ");
            sb.append(c.explanation).append("\n\n```\n").append(c.example).append("\n```\n");
        }
        sb.append("\n## Builtin functions and the standard library\n\n");
        sb.append("Their names are reserved. [SPEC.md](SPEC.md) section 9 describes them in full.\n");
        for (Builtin.Group g : Builtin.Group.values()) {
            sb.append("\n### ").append(g.title).append("\n\n| Signature | Description |\n|-----------|-------------|\n");
            for (Builtin b : Builtin.values()) {
                if (b.group == g) sb.append("| `").append(b.signature).append("` | ").append(b.doc.replace("|", "\\|")).append(" |\n");
            }
        }
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
