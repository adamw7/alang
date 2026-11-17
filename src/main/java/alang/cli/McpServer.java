package alang.cli;

import alang.ast.Ast.FnDecl;
import alang.ast.Ast.Program;
import alang.diag.Source;
import alang.interp.JsonValues;
import alang.interp.Meter;
import alang.types.Type;
import alang.util.Json;
import alang.util.Utf8;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * `alang serve`: a program's tools over the Model Context Protocol's stdio transport. Each line of stdin
 * is one JSON-RPC 2.0 message, and each answer is one line of stdout; nothing else is written there. The
 * program is checked once, at start, so every call in a session runs the same code.
 *
 * <p>Each tools/call runs on a worker thread of its own, up to {@link #WORKERS} at a time, so a long call
 * holds up neither other calls nor the answers to other messages (ping, tools/list, a cancellation); its
 * answer is written when it ends, so answers can come in another order than the requests, as JSON-RPC
 * allows. A call ends within its step budget and its time limit ({@link Watchdog}), and
 * notifications/cancelled stops one early. With {@link Watchdog}, this is the only code that uses threads
 * (see ArchitectureTest): each call is still one deterministic run on one thread.
 *
 * <p>With {@link #watch} (`serve --watch`) a timer polls the program's files and serves the program they hold
 * once it compiles, telling the client with notifications/tools/list_changed when its tools changed. Each
 * message is handled with the program current when it is read, so a call still runs one program from start
 * to end.
 */
final class McpServer {
    /** The protocol revisions this server speaks, newest first. A client asking for another gets the newest. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final String INSTRUCTIONS = "Each tool is an ALang function: the same arguments always give the same "
        + "result, and a call cannot read or change anything outside itself. A result's text is the JSON "
        + "`alang call` prints: ok, then result or error, then stdout and steps (its cost: the statements it ran "
        + "and the data they went through).";

    /** JSON-RPC error codes. */
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    /**
     * The calls that run at once; more wait their turn. A fixed number, not one per processor: the toolchain
     * reads nothing about the machine it runs on (see ArchitectureTest).
     */
    static final int WORKERS = 4;

    private final long maxSteps;
    private final Watchdog watchdog;
    private final int workers;
    /** The program served, which a reload replaces, and its main file. */
    private volatile Loaded current;
    /** Whether the client has sent notifications/initialized: notifications/tools/list_changed waits for it. */
    private volatile boolean initialized;
    /** For `serve --watch`, set by {@link #watch}: the program's files, how often to poll them, and the log; else null. */
    private Reloader reloader;
    private long pollMillis;
    private PrintStream log;
    /**
     * The meter of each call that is waiting or running, by the {@link #key} of its request id, so that a
     * cancellation can stop it.
     */
    private final Map<Object, Meter> calls = new ConcurrentHashMap<>();

    /**
     * A server whose calls each get {@code maxSteps} steps and the time limit of {@code watchdog}, with
     * {@code workers} of them running at once.
     */
    McpServer(Source src, Program program, long maxSteps, Watchdog watchdog, int workers) {
        this.current = new Loaded(src, program);
        this.maxSteps = maxSteps;
        this.watchdog = watchdog;
        this.workers = workers;
    }

    /** A program and its main file, whose Source runs its calls. */
    private record Loaded(Source src, Program program) {}

    /**
     * Makes {@link #serve} poll the program's files with {@code reloader} every {@code pollMillis} ms, and serve
     * the program it compiles when they change; {@code log} gets what each reload found. Call it before serve.
     */
    void watch(Reloader reloader, long pollMillis, PrintStream log) {
        this.reloader = reloader;
        this.pollMillis = pollMillis;
        this.log = log;
    }

    /** A request this server cannot answer with a result: a JSON-RPC error. */
    private static final class RpcException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Answers the messages read from {@code in} until it ends, flushing each answer; then waits for the calls
     * still running, whose answers are written too, and returns.
     */
    void serve(InputStream in, PrintStream out) throws IOException {
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "alang-call");
            t.setDaemon(true);
            return t;
        });
        ScheduledExecutorService ticker = null;
        if (reloader != null) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "alang-watch");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleWithFixedDelay(() -> reload(out), pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        }
        try {
            for (byte[] line = readLine(in); line != null; line = readLine(in)) {
                Map<String, Object> answer = handle(line, pool, out);
                if (answer != null) send(out, answer);
            }
        } finally {
            if (ticker != null) ticker.shutdown();  // no more polls; one under way may finish
            pool.shutdown();
            awaitCalls(pool);
        }
    }

    /**
     * One poll of the program's files: serves the program they now hold, if they changed and it compiled, and
     * tells an initialized client when that changed its tools. A change to a tool's body alone does not.
     */
    private void reload(PrintStream out) {
        try {
            Program p = reloader.poll(log);
            if (p == null) return;
            List<Object> before = tools(current.program());
            current = new Loaded(p.source, p);
            List<Object> after = tools(p);
            if (initialized && !before.equals(after)) {
                send(out, Json.obj("jsonrpc", "2.0", "method", "notifications/tools/list_changed"));
            }
            // Last, so that whoever reads it can count on the new program and its notification.
            log.print("alang: reloaded " + p.source.path + ": " + after.size() + " tool(s)\n");
        } catch (RuntimeException e) {
            // Caught, as one that escaped would end the polling for the rest of the session.
            log.print("alang: reloading failed: " + e + "\n");
        }
    }

    /** Writes one answer as one line. Calls end on their own threads, so one answer is written at a time. */
    private static void send(PrintStream out, Map<String, Object> answer) {
        String line = Json.write(answer) + "\n";
        synchronized (out) {
            out.print(line);
            out.flush();
        }
    }

    /** Waits for the calls still queued or running; if this thread is interrupted, stops them first. */
    private void awaitCalls(ExecutorService pool) {
        boolean interrupted = false;
        while (true) {
            try {
                if (pool.awaitTermination(1, TimeUnit.HOURS)) break;
            } catch (InterruptedException e) {
                interrupted = true;
                for (Meter m : calls.values()) m.stop("cancelled", "the server is shutting down");
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** The next line without its "\n" or "\r\n", or null at the end of the input. */
    private static byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b = in.read();
        if (b < 0) return null;
        while (b >= 0 && b != '\n') {
            line.write(b);
            b = in.read();
        }
        byte[] bytes = line.toByteArray();
        int n = bytes.length;
        if (n > 0 && bytes[n - 1] == '\r') n--;
        return Arrays.copyOf(bytes, n);
    }

    /**
     * The answer to one line: a result or an error for a request, null for a notification, a response
     * from the client (this server sends no requests), or a blank line. A tools/call request is checked here,
     * its arguments too, and then runs on {@code pool}, which writes its answer to {@code out} when it ends:
     * null here too.
     */
    private Map<String, Object> handle(byte[] line, ExecutorService pool, PrintStream out) {
        String text = Utf8.decode(line);
        if (text == null) return error(null, PARSE_ERROR, "the message is not valid UTF-8");
        if (text.isBlank()) return null;
        Object message;
        try {
            message = Json.parse(text);
        } catch (Json.ParseException e) {
            return error(null, PARSE_ERROR, "the message is not valid JSON: " + e.getMessage());
        }
        if (!(message instanceof Map<?, ?> m)) return error(null, INVALID_REQUEST, "a message must be a JSON object");
        Object id = m.get("id");
        boolean validId = id instanceof String || id instanceof BigInteger || id instanceof BigDecimal;
        if (m.containsKey("id") && !validId) return error(null, INVALID_REQUEST, "an id must be a string or a number");
        if (!(m.get("method") instanceof String method)) {
            if (m.containsKey("result") || m.containsKey("error")) return null;  // a response; nothing to answer
            return error(id, INVALID_REQUEST, "a request needs a \"method\" string");
        }
        if (!"2.0".equals(m.get("jsonrpc"))) return error(id, INVALID_REQUEST, "\"jsonrpc\" must be \"2.0\"");
        boolean notification = !m.containsKey("id");
        Loaded now = current;
        Object result;
        try {
            // MCP forbids reusing an id in a session; one still in use would also leave a cancellation unsure
            // which call it stops.
            if (!notification && calls.containsKey(key(id))) {
                throw new RpcException(INVALID_REQUEST, "id " + Json.write(id) + " is in use by a call that has not been answered");
            }
            if (method.equals("tools/call")) {
                FnDecl f = tool(now.program(), m.get("params"));
                // A notification wants no answer, and a call changes nothing, so there is nothing to do.
                return notification ? null : start(id, now.src(), f, m.get("params"), pool, out);
            }
            result = switch (method) {
                case "initialize" -> initialize(m.get("params"));
                case "notifications/initialized" -> {
                    initialized = true;
                    yield Json.obj();
                }
                case "ping" -> Json.obj();
                case "tools/list" -> Json.obj("tools", tools(now.program()));
                case "notifications/cancelled" -> cancel(m.get("params"));
                default -> throw new RpcException(METHOD_NOT_FOUND, "method not found: " + method);
            };
        } catch (RpcException e) {
            return notification ? null : error(id, e.code, e.getMessage());
        }
        return notification ? null : Json.obj("jsonrpc", "2.0", "id", id, "result", result);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message));
    }

    private Map<String, Object> initialize(Object params) {
        Object asked = params instanceof Map<?, ?> p ? p.get("protocolVersion") : null;
        String version = asked instanceof String s && PROTOCOL_VERSIONS.contains(s) ? s : PROTOCOL_VERSIONS.get(0);
        return Json.obj("protocolVersion", version,
            "capabilities", Json.obj("tools", Json.obj("listChanged", reloader != null)),
            "serverInfo", Json.obj("name", "alang", "version", Main.VERSION),
            "instructions", INSTRUCTIONS);
    }

    /**
     * MCP tool definitions: the same description and input schema as `alang tools`, the result's schema,
     * and annotations that are true of every ALang tool: it changes nothing, so it is read-only, not
     * destructive and idempotent, and it reaches nothing outside itself.
     */
    private static List<Object> tools(Program program) {
        List<Object> tools = new ArrayList<>();
        for (FnDecl f : program.itemsOf(FnDecl.class)) {
            if (!f.tool) continue;
            Map<String, Object> tool = Json.obj("name", f.name, "description", ToolDefinitions.description(f),
                "inputSchema", ToolDefinitions.inputSchema(f));
            if (!(f.ret instanceof Type.Void)) {
                tool.put("outputSchema", Json.obj("type", "object", "properties", Json.obj("result", JsonValues.schema(f.ret)),
                    "required", List.of("result"), "additionalProperties", false));
            }
            tool.put("annotations", Json.obj("readOnlyHint", true, "destructiveHint", false,
                "idempotentHint", true, "openWorldHint", false));
            tools.add(tool);
        }
        return tools;
    }

    /** The tool of {@code program} a tools/call names; an unknown one is a protocol error. */
    private static FnDecl tool(Program program, Object params) {
        if (!(params instanceof Map<?, ?> p) || !(p.get("name") instanceof String name)) {
            throw new RpcException(INVALID_PARAMS, "tools/call needs params with a \"name\" string");
        }
        FnDecl f = program.function(name);
        if (f == null || !f.tool) throw new RpcException(INVALID_PARAMS, "unknown tool: " + name);
        return f;
    }

    /**
     * Runs a tool as `alang call` does, on {@code pool}, and writes the answer to request {@code id} when it
     * ends. Its report is the result's text; a successful call of a tool that returns a value also gives
     * {"result": value} as structured content, matching the output schema. A call that failed, from bad
     * arguments to a trap or its time limit, is a result with "isError": true, so the model sees the error and
     * can fix its call; a cancelled call gets no answer, as MCP asks.
     *
     * <p>The arguments are decoded here, before the call waits for a worker: arguments that do not fit are
     * answered at once, the return value, and not after the calls ahead of them. Null when the call was started.
     */
    private Map<String, Object> start(Object id, Source src, FnDecl f, Object params, ExecutorService pool, PrintStream out) {
        Map<?, ?> p = (Map<?, ?>) params;
        // "arguments" is optional, and some clients send null for a tool without parameters.
        Object arguments = p.get("arguments") == null ? Json.obj() : p.get("arguments");
        Object[] args;
        try {
            args = Main.toolArguments(f, arguments);
        } catch (JsonValues.DecodeException e) {
            return Json.obj("jsonrpc", "2.0", "id", id, "result", result(f, Main.badArguments(e.getMessage())));
        }
        Meter meter = new Meter(maxSteps);
        Object key = key(id);
        calls.put(key, meter);
        pool.execute(() -> {
            // What the client hears if the call fails inside the server, from a bug to a full heap, instead of
            // waiting for an answer that never comes; such an error still ends the worker thread.
            Map<String, Object> answer = error(id, INTERNAL_ERROR, "the server failed to run the call");
            try {
                Watchdog.Alarm alarm = watchdog.watch(meter);
                Map<String, Object> report;
                try {
                    report = Main.runTool(src, f, args, meter);
                } finally {
                    alarm.off();
                }
                boolean cancelled = report.get("error") instanceof Map<?, ?> e && "cancelled".equals(e.get("code"));
                answer = cancelled ? null : Json.obj("jsonrpc", "2.0", "id", id, "result", result(f, report));
            } catch (RuntimeException e) {
                answer = error(id, INTERNAL_ERROR, "the server failed to run the call: " + e);
            } finally {
                // Before the answer, so that the client can use the id again as soon as it has the answer.
                calls.remove(key, meter);
                if (answer != null) send(out, answer);
            }
        });
        return null;
    }

    /**
     * What identifies request {@code id} in {@link #calls}: the id itself, but numbers by value, so that a
     * cancellation of 7.0 or 7e0 finds call 7, and 70.0 or 7e1 call 70. Both kinds are stripped of trailing zeros,
     * as BigDecimal.equals compares the scale too: 70 and 7E+1 differ. Without converting to BigInteger, which an
     * id such as 1e999999999 would make huge.
     */
    private static Object key(Object id) {
        if (id instanceof BigInteger i) return new BigDecimal(i).stripTrailingZeros();
        if (id instanceof BigDecimal d) return d.stripTrailingZeros();
        return id;
    }

    private static Map<String, Object> result(FnDecl f, Map<String, Object> report) {
        boolean ok = report.get("ok") == Boolean.TRUE;
        Map<String, Object> result = Json.obj("content", List.of(Json.obj("type", "text", "text", Json.write(report))));
        if (ok && !(f.ret instanceof Type.Void)) result.put("structuredContent", Json.obj("result", report.get("result")));
        result.put("isError", !ok);
        return result;
    }

    /**
     * notifications/cancelled: stops the call with that requestId, if it is still waiting or running, and
     * leaves it unanswered. One that has ended, or that this server never had, is ignored, as MCP asks.
     */
    private Map<String, Object> cancel(Object params) {
        if (params instanceof Map<?, ?> p && p.get("requestId") instanceof Object requestId) {
            Meter meter = calls.get(key(requestId));
            if (meter != null) meter.stop("cancelled", "the client cancelled the call");
        }
        return Json.obj();
    }
}
