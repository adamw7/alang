package alang.cli;

import alang.ast.Ast.*;
import alang.check.Expressions;
import alang.diag.*;
import alang.interp.*;
import alang.parse.Parser;
import alang.types.*;
import alang.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * `alang debug`: runs fn main, a test or a tool under a debugger driven by commands, one per line, from a
 * reader (stdin, or a script). It stops at breakpoints, steps a statement at a time, and shows the stack and
 * the variables of each frame, also where a trap happens, before the stack unwinds.
 *
 * <p>When the commands run out the run goes on by itself: each later stop is shown with its variables and
 * passed, up to {@code maxStops}, so `alang debug prog.al --break 12 < /dev/null` prints every state line 12
 * sees. Everything is deterministic: the same program, commands and input give the same transcript.
 */
final class Debug implements Debugger {
    /** The longest value shown, in characters; the rest is counted, not shown. */
    static final int MAX_VALUE_CHARS = 2000;

    static final String HELP = """
        commands (a resuming command runs the program until the next stop):
          break LOC      b   stop at LOC: a line (12), a line of an imported file (lib/fmt.al:12),
                             or the first statement of a function (add_word); `break LOC if COND`
                             stops only where COND is true, as `break 12 if i == 3`
          delete [N]     d   delete breakpoint N, or all of them
          breakpoints        list the breakpoints and how often each was hit
          run            r   start the program (it then stops only at breakpoints and traps)
          continue       c   run to the next breakpoint, or the end
          step           s   run one statement, stepping into calls
          next           n   run to the next statement of this function, stepping over calls
          finish         f   run until this function returns to its caller
          where          bt  the call stack, innermost first
          frame N            look at frame N of the stack (0 is the innermost)
          locals         v   the variables in scope in the frame, with their values
          print EXPR     p   the value of an expression, in the scope of the frame: xs[i].name,
                             len(xs) - 1, total(xs), m["key"]; it cannot change the program
          list [LINE]    l   the source around the stop, or around LINE
          display            also show the variables at every stop (again to stop showing them)
          help           h   this text
          quit           q   stop the program and end the session
        """;

    private enum Mode { CONTINUE, STEP, NEXT, FINISH }

    /**
     * A breakpoint; {@code condition} is the text of its `if`, or null, and {@code checked} the condition checked for
     * each scope it was reached in.
     */
    private record Breakpoint(int id, Source file, int line, String function, String condition, int[] hits,
                              Map<List<Object>, Checked> checked) {
        String where() {
            return (function != null ? "fn " + function : file.path + ":" + line) + (condition == null ? "" : " if " + condition);
        }
    }

    /** An expression typed at a stop, checked for its frame: what to evaluate, and the frame to evaluate it in. */
    private record Checked(Expr expr, Routine frame) {}

    /** An expression that could not be checked or evaluated: what to say, and how to fix it. */
    private static final class EvalError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String hint;

        EvalError(String message, String hint) {
            super(message, null, false, false);
            this.hint = hint;
        }
    }

    /** The budget of an expression typed at a stop, in steps; it is not charged to the program. */
    static final long EXPRESSION_STEPS = 10_000_000L;

    private static final class Quit extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Quit() {
            super(null, null, false, false);
        }
    }

    private final Source src;
    private final Program program;
    private final BufferedReader commands;
    private final PrintStream out;
    private final boolean json;
    private final boolean interactive;
    private final int maxStops;
    /** With --json the program's output is held here and shown as an "output" event before each stop. */
    private final ByteArrayOutputStream held;
    private final Expressions expressions;
    /** For each file, the lines a statement starts on. */
    private final Map<Source, TreeSet<Integer>> lines = new java.util.HashMap<>();

    private final List<Breakpoint> breakpoints = new ArrayList<>();
    private int nextId = 1;
    private Interpreter interp;
    private Mode mode = Mode.CONTINUE;
    private int targetDepth;
    private boolean started;
    /** The commands ran out: stops are shown, with their variables, and passed. */
    private boolean auto;
    private boolean display;
    private int stops;
    private boolean stopsExhausted;
    /** At a stop: its stack, and the frame the commands look at. */
    private List<Frame> stack = List.of();
    private int selected;

    Debug(Source src, Program program, BufferedReader commands, PrintStream out, boolean json, boolean interactive,
          int maxStops, ByteArrayOutputStream held, Expressions expressions) {
        this.src = src;
        this.program = program;
        this.commands = commands;
        this.out = out;
        this.json = json;
        this.interactive = interactive;
        this.maxStops = maxStops;
        this.held = held;
        this.expressions = expressions;
        for (Routine r : program.itemsOf(Routine.class)) collect(r.body);
    }

    private void collect(Block b) {
        for (Stmt s : b.stmts()) {
            Source f = s.span.file() != null ? s.span.file() : src;
            lines.computeIfAbsent(f, k -> new TreeSet<>()).add(s.span.line());
            switch (s) {
                case If i -> {
                    i.blocks.forEach(this::collect);
                    if (i.elseBlock != null) collect(i.elseBlock);
                }
                case While w -> collect(w.body);
                case For f2 -> collect(f2.body);
                case Destructure d when d.elseBlock != null -> collect(d.elseBlock);
                case Match m -> {
                    for (Arm a : m.arms) collect(a.body());
                }
                default -> { }
            }
        }
    }

    /** The outcome of the run, for the session's last event. */
    interface Target {
        /** Runs the program; returns the text of a tool's result, or null. */
        Object run(Interpreter in);
    }

    /**
     * The session: commands until the program starts, the run with its stops, and its outcome. Returns the exit
     * code: 0 when the program ran to its end, 1 when it trapped, also when the session was quit.
     */
    int session(Interpreter in, Target target, List<String> initialBreaks) {
        this.interp = in;
        in.attach(this);
        for (String b : initialBreaks) addBreak(b);
        try {
            commandLoop();
        } catch (Quit q) {
            event(Json.obj("event", "quit"), "session ended before the program started");
            return 0;
        }
        started = true;
        Object result = null;
        Trap trap = null;
        boolean quit = false;
        try {
            result = target.run(in);
        } catch (Trap t) {
            trap = t;
        } catch (Quit q) {
            quit = true;
        }
        in.flushOutput();
        emitOutput();
        if (quit) {
            event(Json.obj("event", "quit", "steps", in.steps()), "program stopped by quit after " + in.steps() + " step(s)");
            return 1;
        }
        if (trap != null) {
            if (json) {
                line(Json.write(Json.obj("event", "exited", "ok", false, "trap", Main.trapJson(src, trap), "steps", in.steps())));
            } else {
                out.writeBytes(Main.renderTrapBytes(src, trap));
                line("program trapped after " + in.steps() + " step(s)");
            }
            return 1;
        }
        Map<String, Object> j = Json.obj("event", "exited", "ok", true);
        String text = "program exited normally after " + in.steps() + " step(s)";
        if (result instanceof Main.ToolResult tr) {
            j.put("result", tr.json());
            text = "result: " + tr.text() + "\n" + text;
        }
        j.put("steps", in.steps());
        event(j, text);
        return 0;
    }

    // =================================================================== hooks

    @Override
    public void statement(Stmt s, boolean lineStart, Frame top, int depth) {
        Breakpoint bp = null;
        String conditionError = null;
        for (Breakpoint b : stopsExhausted ? List.<Breakpoint>of() : breakpointsAt(s, lineStart, top)) {
            try {
                if (b.condition != null && !condition(b, top)) continue;
            } catch (EvalError e) {
                // A condition that checks but cannot be worked out here, as one that traps, stops, to say so.
                conditionError = e.getMessage();
            }
            bp = b;
            break;
        }
        boolean stepped = switch (mode) {
            case CONTINUE -> false;
            case STEP -> true;
            case NEXT -> depth <= targetDepth;
            case FINISH -> depth < targetDepth;
        };
        if (bp == null && !stepped) return;
        if (bp != null) bp.hits[0]++;
        if (!admit()) return;
        List<Frame> frames = interp.frames();
        stopAt(frames);
        Span at = s.span;
        Map<String, Object> j = Json.obj("event", "stopped", "reason", bp != null ? "breakpoint" : "step");
        if (bp != null) j.put("breakpoint", bp.id);
        if (conditionError != null) j.put("condition_error", conditionError);
        String head = (bp != null ? "stopped at breakpoint " + bp.id : "stepped") + " in " + frames.get(0).name() + ", "
            + where(at);
        placeJson(j, at);
        j.put("function", frames.get(0).name());
        j.put("steps", interp.steps());
        if (auto || display) j.put("variables", variablesJson(frames.get(0)));
        if (json) {
            line(Json.write(j));
        } else {
            line(head);
            line(sourceLine(at, "  "));
            if (conditionError != null) line("  the condition `" + bp.condition + "` could not be evaluated: " + conditionError);
            if (auto || display) printVariables(frames.get(0));
        }
        pause();
    }

    /** Whether the condition of {@code bp} holds in {@code top}. */
    private boolean condition(Breakpoint bp, Frame top) {
        List<Object> key = new ArrayList<>();
        key.add(top.routine());
        key.addAll(top.scope());
        Checked c = bp.checked.get(key);
        if (c == null) {
            try {
                c = check(bp.condition, top);
                if (!(c.expr.type instanceof Type.Prim p && p.kind() == PrimKind.BOOL)) {
                    throw new EvalError("it is a " + c.expr.type + ", not a bool", "write a comparison, such as `i == 3`");
                }
            } catch (EvalError e) {
                // Where the condition does not check, such as on the line of a loop before its variable is declared,
                // the breakpoint does not stop: that is said once for each scope, and the run goes on.
                c = new Checked(null, null);
                Span at = top.at();
                Map<String, Object> j = Json.obj("event", "condition_skipped", "breakpoint", bp.id, "condition", bp.condition,
                    "message", e.getMessage(), "hint", e.hint);
                placeJson(j, at);
                event(j, "breakpoint " + bp.id + " does not stop at " + where(at) + ": its condition `" + bp.condition
                    + "` does not check there: " + e.getMessage() + "\n  = hint: " + e.hint);
            }
            bp.checked.put(key, c);
        }
        if (c.expr == null) return false;
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        try {
            return (Boolean) top.evaluate(c.expr, c.frame, EXPRESSION_STEPS, ignored);
        } catch (Trap t) {
            throw new EvalError("trap[" + t.code.name() + "]: " + t.getMessage(), "make the condition safe where it is reached");
        }
    }

    /** Parses and checks {@code text} as an expression written at the statement {@code f} is at. */
    private Checked check(String text, Frame f) {
        String body = text.strip();
        while (body.endsWith(";")) body = body.substring(0, body.length() - 1).strip();
        if (body.isEmpty()) throw new EvalError("expected an expression", "write a variable, such as `xs`, or an expression, such as `len(xs) - 1`");
        // The expression is the only statement of a function of its own, on a line of its own, so that its columns
        // are those of the text typed.
        Source wrap = new Source("<expression>", "fn debugger() {\n" + body + ";\n}\n");
        Diagnostics d = new Diagnostics(wrap);
        Parser.Parsed parsed = Parser.parse(wrap, d, false);
        if (parsed == null) throw firstError(d, "write one expression, such as `xs[i].name` or `len(xs) - 1`");
        List<Stmt> stmts = parsed.program.itemsOf(FnDecl.class).get(0).body.stmts();
        if (stmts.size() != 1 || !(stmts.get(0) instanceof ExprStmt es)) {
            throw new EvalError("expected one expression, found `" + body + "`", "write one expression, such as `xs[i].name`");
        }
        Diagnostics errors = new Diagnostics(wrap);
        Routine frame = expressions.check(es.expr, f.routine(), f.scope(), errors);
        if (frame == null) throw firstError(errors, "`locals` lists the variables in scope here");
        return new Checked(es.expr, frame);
    }

    private static EvalError firstError(Diagnostics d, String fallback) {
        for (Diagnostic x : d.all()) {
            if (x.isError()) {
                return new EvalError(x.message() + " (column " + x.span().col() + ")", x.hint() == null || x.hint().isEmpty() ? fallback : x.hint());
            }
        }
        return new EvalError("the expression is not valid", fallback);
    }

    @Override
    public void trap(Trap t, List<Frame> frames) {
        if (stopsExhausted) return;
        if (!admit()) return;
        stopAt(frames);
        Span at = frames.get(0).at();
        Map<String, Object> j = Json.obj("event", "stopped", "reason", "trap", "code", t.code.name(), "message", t.getMessage());
        placeJson(j, at);
        j.put("function", frames.get(0).name());
        j.put("steps", interp.steps());
        j.put("variables", variablesJson(frames.get(0)));
        if (json) {
            line(Json.write(j));
        } else {
            line("trapped in " + frames.get(0).name() + ", " + where(at) + ": trap[" + t.code.name() + "]: " + t.getMessage());
            line(sourceLine(at, "  "));
            printVariables(frames.get(0));
        }
        // After a trap the program can only end: every resuming command lets it.
        pause();
        mode = Mode.CONTINUE;
        stopsExhausted = true;
    }

    /** Counts a stop; false, after a note, once --max-stops is reached with no commands left to read. */
    private boolean admit() {
        if (auto && stops >= maxStops) {
            stopsExhausted = true;
            mode = Mode.CONTINUE;
            interp.flushOutput();
            emitOutput();
            event(Json.obj("event", "stops_exhausted", "max_stops", maxStops),
                "reached " + maxStops + " stop(s) (--max-stops); the program runs on without stopping");
            return false;
        }
        stops++;
        return true;
    }

    private void stopAt(List<Frame> frames) {
        interp.flushOutput();
        emitOutput();
        stack = frames;
        selected = 0;
    }

    /** Commands until one resumes the run; with the commands used up, the run resumes at once. */
    private void pause() {
        if (auto) {
            mode = Mode.CONTINUE;
            return;
        }
        commandLoop();
    }

    /** The breakpoints at statement {@code s}, in the order they were set. */
    private List<Breakpoint> breakpointsAt(Stmt s, boolean lineStart, Frame top) {
        Source file = s.span.file() != null ? s.span.file() : src;
        List<Breakpoint> found = new ArrayList<>();
        for (Breakpoint b : breakpoints) {
            if (b.function != null) {
                Routine r = top.routine();
                if (r instanceof FnDecl f && f.name.equals(b.function) && !r.body.stmts().isEmpty() && r.body.stmts().get(0) == s) {
                    found.add(b);
                }
            } else if (lineStart && b.file == file && b.line == s.span.line()) {
                found.add(b);
            }
        }
        return found;
    }

    // =================================================================== commands

    /** Reads and runs commands until one resumes the program (or starts it). */
    private void commandLoop() {
        while (true) {
            if (interactive && !json) {
                out.print("(alang) ");
                out.flush();
            }
            String cmd;
            try {
                cmd = commands.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (cmd == null) {
                auto = true;
                mode = Mode.CONTINUE;
                return;
            }
            cmd = cmd.strip();
            if (cmd.isEmpty() || cmd.startsWith("#")) continue;
            if (!interactive && !json) line("(alang) " + cmd);
            if (execute(cmd)) return;
        }
    }

    /** Runs one command; true when it resumes the program. */
    private boolean execute(String line) {
        int sp = line.indexOf(' ');
        String word = sp < 0 ? line : line.substring(0, sp);
        String arg = sp < 0 ? "" : line.substring(sp + 1).strip();
        boolean atStop = started && !stack.isEmpty();
        switch (word) {
            case "help", "h" -> {
                if (json) line(Json.write(Json.obj("event", "help", "text", HELP)));
                else out.print(HELP);
                return false;
            }
            case "break", "b" -> {
                if (arg.isEmpty()) return error("`break` needs a location", "write `break 12`, `break lib/fmt.al:12` or `break add_word`");
                addBreak(arg);
                return false;
            }
            case "delete", "d" -> {
                delete(arg);
                return false;
            }
            case "breakpoints", "info" -> {
                listBreakpoints();
                return false;
            }
            case "run", "r" -> {
                if (started) return error("the program is already running", "use `continue`, `step`, `next` or `finish`");
                mode = Mode.CONTINUE;
                return true;
            }
            case "continue", "c" -> {
                mode = Mode.CONTINUE;
                return true;
            }
            case "step", "s" -> {
                mode = Mode.STEP;
                return true;
            }
            case "next", "n" -> {
                mode = started ? Mode.NEXT : Mode.STEP;
                targetDepth = stack.isEmpty() ? 1 : stack.size();
                return true;
            }
            case "finish", "f" -> {
                if (!atStop) return error("the program has not started", "use `run` or `step` first");
                mode = Mode.FINISH;
                targetDepth = stack.size();
                return true;
            }
            case "quit", "q" -> throw new Quit();
            case "display" -> {
                display = !display;
                event(Json.obj("event", "display", "on", display),
                    display ? "the variables are shown at every stop" : "the variables are no longer shown at every stop");
                return false;
            }
            default -> { }
        }
        if (!atStop) {
            if (word.equals("list") || word.equals("l")) {
                list(arg, null);
                return false;
            }
            if (List.of("where", "bt", "frame", "locals", "v", "print", "p").contains(word)) {
                return error("the program has not started, so `" + word + "` has nothing to show", "use `run` or `step` first");
            }
            return unknown(word);
        }
        switch (word) {
            case "where", "bt" -> where();
            case "frame" -> {
                int n;
                try {
                    n = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    return error("`frame` needs a number, found `" + arg + "`", "write `frame 1`; `where` lists the frames");
                }
                if (n < 0 || n >= stack.size()) {
                    return error("no frame " + n + ": the stack has " + stack.size() + " frame(s), 0 to " + (stack.size() - 1),
                        "`where` lists the frames");
                }
                selected = n;
                Frame f = stack.get(n);
                Map<String, Object> j = Json.obj("event", "frame", "frame", n, "function", f.name());
                placeJson(j, f.at());
                if (json) {
                    line(Json.write(j));
                } else {
                    line("#" + n + " " + f.name() + ", " + where(f.at()));
                    line(sourceLine(f.at(), "  "));
                }
            }
            case "locals", "v" -> {
                Frame f = stack.get(selected);
                if (json) line(Json.write(Json.obj("event", "variables", "frame", selected, "variables", variablesJson(f))));
                else printVariables(f);
            }
            case "print", "p" -> {
                if (arg.isEmpty()) return error("`print` needs an expression", "write `print x`, `print xs[0]` or `print len(xs) - 1`");
                Frame f = stack.get(selected);
                ByteArrayOutputStream printed = new ByteArrayOutputStream();
                Checked c;
                Object v;
                try {
                    c = check(arg, f);
                    try {
                        v = f.evaluate(c.expr, c.frame, EXPRESSION_STEPS, printed);
                    } catch (Trap t) {
                        throw new EvalError("trap[" + t.code.name() + "]: " + t.getMessage(), t.code.explanation);
                    }
                } catch (EvalError e) {
                    return error(e.getMessage(), e.hint);
                }
                String value = Frame.text(v, c.expr.type);
                String out2 = printed.toString(StandardCharsets.UTF_8);
                if (json) {
                    Map<String, Object> j = Json.obj("event", "value", "expression", arg.strip(), "type", c.expr.type.toString(),
                        "value", clip(value));
                    if (value.length() > MAX_VALUE_CHARS) j.put("length", value.length());
                    if (!out2.isEmpty()) j.put("output", out2);
                    line(Json.write(j));
                } else {
                    if (!out2.isEmpty()) out.print(out2.endsWith("\n") ? out2 : out2 + "\n");
                    line(arg.strip() + ": " + c.expr.type + " = " + clip(value));
                }
            }
            case "list", "l" -> list(arg, stack.get(selected).at());
            default -> {
                return unknown(word);
            }
        }
        return false;
    }

    private boolean unknown(String word) {
        return error("unknown command `" + word + "`", "`help` lists the commands");
    }

    private boolean error(String message, String hint) {
        if (json) line(Json.write(Json.obj("event", "error", "message", message, "hint", hint)));
        else line("error: " + message + "\n  = hint: " + hint);
        return false;
    }

    private void addBreak(String spec) {
        String loc = spec.strip();
        String condition = null;
        int cond = loc.indexOf(" if ");
        if (cond >= 0) {
            condition = loc.substring(cond + 4).strip();
            loc = loc.substring(0, cond).strip();
            if (condition.isEmpty()) {
                error("`break " + loc + " if` needs a condition", "write `break " + loc + " if i == 3`");
                return;
            }
        }
        Source file = src;
        String lineText = loc;
        int colon = loc.lastIndexOf(':');
        if (colon > 0) {
            String path = loc.substring(0, colon);
            lineText = loc.substring(colon + 1);
            file = null;
            for (Source f : program.files) {
                if (f.path.equals(path) || f.path.endsWith("/" + path) || f.path.endsWith("\\" + path)) file = f;
            }
            if (file == null) {
                List<String> paths = new ArrayList<>();
                for (Source f : program.files) paths.add(f.path);
                error("no file `" + path + "` in the program", "its files are: " + String.join(", ", paths));
                return;
            }
        }
        if (!lineText.isEmpty() && lineText.chars().allMatch(Character::isDigit)) {
            int n;
            try {
                n = Integer.parseInt(lineText);
            } catch (NumberFormatException e) {
                error("no line " + lineText + " in " + file.path, "a breakpoint goes on a line a statement starts on");
                return;
            }
            TreeSet<Integer> starts = lines.getOrDefault(file, new TreeSet<>());
            if (!starts.contains(n)) {
                Integer before = starts.lower(n);
                Integer after = starts.higher(n);
                List<String> near = new ArrayList<>();
                if (before != null) near.add(String.valueOf(before));
                if (after != null) near.add(String.valueOf(after));
                error("no statement starts on line " + n + " of " + file.path,
                    near.isEmpty() ? file.path + " has no statements"
                        : near.size() == 1 ? "the nearest line with one is " + near.get(0)
                        : "the nearest lines with one are " + String.join(" and ", near));
                return;
            }
            Breakpoint b = new Breakpoint(nextId++, file, n, null, condition, new int[1], new HashMap<>());
            breakpoints.add(b);
            Map<String, Object> j = Json.obj("event", "breakpoint", "id", b.id, "file", file.path, "line", n);
            if (condition != null) j.put("condition", condition);
            event(j, "breakpoint " + b.id + " at " + b.where());
            return;
        }
        if (colon > 0 || !isName(loc)) {
            error("`" + loc + "` is not a location", "write a line (12), a line of a file (lib/fmt.al:12) or a function name");
            return;
        }
        TreeMap<String, FnDecl> fns = new TreeMap<>();
        for (FnDecl f : program.itemsOf(FnDecl.class)) {
            if (!f.builtin) fns.put(f.name, f);
        }
        if (!fns.containsKey(loc)) {
            error("no function `" + loc + "`", "the functions are: " + String.join(", ", fns.keySet()));
            return;
        }
        Breakpoint b = new Breakpoint(nextId++, null, 0, loc, condition, new int[1], new HashMap<>());
        breakpoints.add(b);
        Map<String, Object> j = Json.obj("event", "breakpoint", "id", b.id, "function", loc);
        if (condition != null) j.put("condition", condition);
        event(j, "breakpoint " + b.id + " at " + b.where());
    }

    private static boolean isName(String s) {
        if (s.isEmpty() || !(Character.isLetter(s.charAt(0)) || s.charAt(0) == '_')) return false;
        return s.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }

    private void delete(String arg) {
        if (arg.isEmpty()) {
            breakpoints.clear();
            event(Json.obj("event", "deleted", "all", true), "deleted every breakpoint");
            return;
        }
        for (Breakpoint b : breakpoints) {
            if (String.valueOf(b.id).equals(arg)) {
                breakpoints.remove(b);
                event(Json.obj("event", "deleted", "id", b.id), "deleted breakpoint " + b.id);
                return;
            }
        }
        error("no breakpoint " + arg, "`breakpoints` lists them");
    }

    private void listBreakpoints() {
        List<Object> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Breakpoint b : breakpoints) {
            Map<String, Object> j = Json.obj("id", b.id);
            if (b.function != null) j.put("function", b.function);
            else {
                j.put("file", b.file.path);
                j.put("line", b.line);
            }
            if (b.condition != null) j.put("condition", b.condition);
            j.put("hits", b.hits[0]);
            list.add(j);
            sb.append(b.id).append(": ").append(b.where()).append(", hit ").append(b.hits[0]).append(" time(s)\n");
        }
        if (json) line(Json.write(Json.obj("event", "breakpoints", "breakpoints", list)));
        else out.print(breakpoints.isEmpty() ? "no breakpoints\n" : sb.toString());
    }

    private void where() {
        List<Object> frames = new ArrayList<>();
        for (int i = 0; i < stack.size(); i++) {
            Frame f = stack.get(i);
            Map<String, Object> j = Json.obj("frame", i, "function", f.name());
            placeJson(j, f.at());
            frames.add(j);
            if (!json) line((i == selected ? "* " : "  ") + "#" + i + " " + f.name() + ", " + where(f.at()));
        }
        if (json) line(Json.write(Json.obj("event", "stack", "frames", frames)));
    }

    private void list(String arg, Span at) {
        Source file = at != null && at.file() != null ? at.file() : src;
        int center;
        if (arg.isEmpty()) {
            if (at == null) {
                error("`list` needs a line before the program starts", "write `list 12`");
                return;
            }
            center = at.line();
        } else {
            try {
                center = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                error("`list` takes a line number, found `" + arg + "`", "write `list 12`");
                return;
            }
        }
        int from = Math.max(1, center - 4);
        int to = Math.min(file.lineCount(), center + 4);
        List<Object> rows = new ArrayList<>();
        int width = String.valueOf(to).length();
        for (int n = from; n <= to; n++) {
            boolean current = at != null && n == at.line() && (at.file() == null || at.file() == file);
            rows.add(Json.obj("line", n, "text", file.line(n), "current", current));
            if (!json) {
                String no = " ".repeat(width - String.valueOf(n).length()) + n;
                line((current ? "=> " : "   ") + no + " | " + file.line(n));
            }
        }
        if (json) line(Json.write(Json.obj("event", "source", "file", file.path, "lines", rows)));
    }

    // =================================================================== output

    private void printVariables(Frame f) {
        List<Frame.Variable> vars = f.variables();
        if (vars.isEmpty()) line("  (no variables)");
        for (Frame.Variable v : vars) line("  " + v.name() + ": " + v.type() + " = " + clip(v.value()));
    }

    private List<Object> variablesJson(Frame f) {
        List<Object> list = new ArrayList<>();
        for (Frame.Variable v : f.variables()) list.add(variableJson(v, null));
        return list;
    }

    private static Map<String, Object> variableJson(Frame.Variable v, String event) {
        Map<String, Object> j = event == null ? Json.obj("name", v.name()) : Json.obj("event", event, "name", v.name());
        j.put("type", v.type().toString());
        j.put("value", clip(v.value()));
        if (v.value().length() > MAX_VALUE_CHARS) j.put("length", v.value().length());
        return j;
    }

    static String clip(String value) {
        if (value.length() <= MAX_VALUE_CHARS) return value;
        int cut = MAX_VALUE_CHARS;
        if (Character.isHighSurrogate(value.charAt(cut - 1))) cut--;
        return value.substring(0, cut) + "… (" + (value.length() - cut) + " more characters)";
    }

    private void placeJson(Map<String, Object> j, Span at) {
        Source file = at.file() != null ? at.file() : src;
        j.put("file", file.path);
        j.put("line", at.line());
        j.put("column", at.col());
        j.put("source_line", file.line(at.line()));
    }

    private String where(Span at) {
        Source file = at.file() != null ? at.file() : src;
        return file.path + ":" + at.line() + ":" + at.col();
    }

    private String sourceLine(Span at, String indent) {
        Source file = at.file() != null ? at.file() : src;
        return indent + at.line() + " | " + file.line(at.line());
    }

    private void emitOutput() {
        if (held == null || held.size() == 0) return;
        line(Json.write(Json.obj("event", "output", "text", held.toString(StandardCharsets.UTF_8))));
        held.reset();
    }

    private void event(Map<String, Object> j, String text) {
        line(json ? Json.write(j) : text);
    }

    private void line(String s) {
        out.print(s);
        out.print('\n');
        out.flush();
    }
}
