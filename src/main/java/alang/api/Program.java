package alang.api;

import alang.ast.Ast.FnDecl;
import alang.cli.*;
import alang.diag.Source;
import alang.interp.*;
import alang.types.Type;
import alang.util.Json;
import java.util.*;

/**
 * A compiled program. Immutable and safe to share: any number of threads may call its tools at once, and a
 * call reaches nothing outside itself (no files, network, clock or randomness).
 */
public final class Program {
    private final Source source;
    private final alang.ast.Ast.Program program;
    private final List<Tool> tools;

    /** One timer thread for the time limits of every call in the JVM, started on the first call that has one. */
    private static final class Timer {
        static final Watchdog WATCHDOG = new Watchdog(1);
    }

    Program(Source source, alang.ast.Ast.Program program) {
        this.source = source;
        this.program = program;
        List<Tool> list = new ArrayList<>();
        for (FnDecl f : program.itemsOf(FnDecl.class)) {
            if (!f.tool) continue;
            String output = f.ret instanceof Type.Void ? null : Json.write(JsonValues.schema(f.ret));
            list.add(new Tool(f.name, ToolDefinitions.description(f), Json.write(ToolDefinitions.inputSchema(f)), output));
        }
        this.tools = List.copyOf(list);
    }

    /** The path of the program's main file. */
    public String path() {
        return source.path;
    }

    /** Its `tool fn` functions, in the order they are declared. */
    public List<Tool> tools() {
        return tools;
    }

    public Optional<Tool> tool(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** The definitions of all tools as a JSON array in the Messages API's shape (see {@link Tool#toJson}). */
    public String toolsJson() {
        List<Object> list = new ArrayList<>();
        for (Tool t : tools) list.add(Json.parse(t.toJson()));
        return Json.write(list);
    }

    /** Calls tool {@code name} with a JSON object of arguments, under the default limits. */
    public CallResult call(String name, String argumentsJson) {
        return call(name, argumentsJson, CallOptions.defaults());
    }

    /**
     * Calls tool {@code name} with {@code argumentsJson}, a JSON object holding each parameter (one with a
     * default value may be left out), as `alang call` does. Never throws for anything the arguments or the
     * program do: an unknown tool, bad JSON, arguments that do not fit, a trap, the step budget, the time limit
     * and a cancellation all come back as a CallResult that is not {@link CallResult#ok}.
     */
    public CallResult call(String name, String argumentsJson, CallOptions options) {
        if (name == null || argumentsJson == null || options == null) {
            throw new NullPointerException("name, argumentsJson and options are required");
        }
        FnDecl f = program.function(name);
        if (f == null || !f.tool) {
            List<String> names = tools.stream().map(Tool::name).toList();
            String msg = f == null ? "no function `" + name + "`" : "`" + name + "` is not declared as `tool fn`";
            return failure("bad_tool", msg + "; available tools: " + names);
        }
        Object parsed;
        try {
            parsed = Json.parse(argumentsJson);
        } catch (Json.ParseException e) {
            return failure("bad_json", "arguments are not valid JSON: " + e.getMessage());
        }
        Object[] args;
        try {
            args = Main.toolArguments(f, parsed);
        } catch (JsonValues.DecodeException e) {
            return new CallResult(Main.badArguments(e.getMessage()));
        }
        Meter meter = new Meter(options.maxSteps());
        Cancellation c = options.cancellation();
        if (c != null) c.attach(meter);
        Watchdog.Alarm alarm = options.timeoutMillis() > 0
            ? Timer.WATCHDOG.watch(meter, options.timeoutMillis(), "")
            : () -> { };
        try {
            return new CallResult(Main.runTool(source, f, args, meter, HostBridge.of(options.host())));
        } finally {
            alarm.off();
            if (c != null) c.detach(meter);
        }
    }

    private static CallResult failure(String code, String message) {
        Map<String, Object> report = Json.obj("ok", false, "error", Json.obj("code", code, "message", message));
        return new CallResult(report);
    }
}
