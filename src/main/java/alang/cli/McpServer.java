package alang.cli;

import alang.ast.Ast.*;
import alang.diag.Source;
import alang.interp.*;
import alang.types.Type;
import alang.util.*;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * `alang serve`: a program's tools over the Model Context Protocol. With the stdio transport ({@link #serve}),
 * each line of stdin is one JSON-RPC 2.0 message, and each answer is one line of stdout; nothing else is
 * written there. {@link HttpTransport} serves the same messages over Streamable HTTP, one {@link Session}
 * per client. The program is checked once, at start, so every call in a session runs the same code.
 *
 * <p>Each tools/call runs on a worker thread of its own, up to {@link #WORKERS} at a time, so a long call
 * holds up neither other calls nor the answers to other messages (ping, tools/list, a cancellation); its
 * answer is written when it ends, so answers can come in another order than the requests, as JSON-RPC
 * allows. A call ends within its step budget and its time limit ({@link Watchdog}), and
 * notifications/cancelled stops one early. At most {@code maxQueued} calls wait for a worker; one more is
 * refused at once with {@link #SERVER_BUSY}, so a flood of calls cannot fill the heap. With {@link Watchdog}, this is the only code that uses threads
 * (see ArchitectureTest): each call is still one deterministic run on one thread.
 *
 * <p>With {@link #watch} (`serve --watch`) a timer polls the program's files and serves the program they hold
 * once it compiles, telling the client with notifications/tools/list_changed when its tools changed. Each
 * message is handled with the program current when it is read, so a call still runs one program from start
 * to end.
 *
 * <p>With {@link #skills} (`serve --skills DIR`) it also serves Agent Skills as the MCP Skills extension
 * ({@link Skills#EXTENSION}) defines: skills/list and skills/get describe them, and their files are resources,
 * listed with resources/list, read with resources/read and browsed with resources/directory/read. With
 * --watch, the skills are polled too, and a change is told with notifications/resources/list_changed.
 *
 * <p>A client that offers the Tasks extension ({@link Tasks#EXTENSION}) gets a task for a call still running
 * {@link #TASK_AFTER_MILLIS} after it was asked for, instead of waiting for its end: the request is answered
 * with the task, and the client polls it with tasks/get, which gives the call's result once it ends, or stops
 * it with tasks/cancel. A call that ends sooner is answered as usual.
 *
 * <p>It speaks both eras of MCP. A legacy client (revisions up to 2025-11-25) opens with initialize, which
 * fixes its revision and capabilities for the session. A modern one ({@link #MODERN_VERSIONS}) sends neither:
 * each request names its revision and capabilities in its params' _meta, and is served on its own, its result
 * carrying "resultType" and the server's name in its _meta; server/discover says what the server speaks, and
 * subscriptions/listen opens a stream of the change notifications the client asks for.
 */
final class McpServer {
    /** The stateless revisions this server speaks, newest first: each request names its own in its _meta. */
    static final List<String> MODERN_VERSIONS = List.of("2026-07-28");

    /** The revisions that open with initialize, newest first. A client asking for another gets the newest. */
    static final List<String> LEGACY_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    /** Every revision this server speaks, newest first. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final String INSTRUCTIONS = "Each tool is an ALang function: the same arguments always give the same "
        + "result, and a call cannot read or change anything outside itself. A result's text is the JSON "
        + "`alang call` prints: ok, then result or error, then stdout and steps (its cost: the statements it ran "
        + "and the data they went through).";

    /** What {@link #INSTRUCTIONS} adds for a program with host fns. */
    static final String HOST_INSTRUCTIONS = " A tool whose description says it asks the host reaches outside data "
        + "through it, so a repeated call may give another result; its text also lists host_calls, each question "
        + "asked and its answer.";

    /** What {@link #INSTRUCTIONS} adds for a server with skills, before their URIs. */
    static final String SKILL_INSTRUCTIONS = " Its skills say how to use the tools for a task: read one with resources/read "
        + "when its task comes up (skills/list describes each): ";

    /** How many skills the instructions name; skills/list has them all. */
    static final int INSTRUCTION_SKILLS = 16;

    /**
     * How long a client may keep a listing, a skill file or what server/discover says before asking again
     * (CacheableResult's ttlMs); 0 for what --watch may change.
     */
    static final long SKILLS_TTL_MILLIS = 300_000;

    /** JSON-RPC error codes. */
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;
    /** Server-defined JSON-RPC errors: every worker is busy and the queue is full; a call was cancelled. */
    static final int SERVER_BUSY = -32001;
    static final int REQUEST_CANCELLED = -32800;
    /** MCP's own errors (2026-07-28): HTTP headers that do not match the body; a revision this server does not speak. */
    static final int HEADER_MISMATCH = -32020;
    static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /** How long a call may run before a client that offers the Tasks extension gets a task for it instead. */
    static final long TASK_AFTER_MILLIS = 1000;

    /** Where a request carries the client's capabilities in its params' _meta (the stateless revisions). */
    static final String CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";

    /** Where a modern request names its revision, in its params' _meta. */
    static final String PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";

    /** Where a modern result names the server, in its _meta. */
    static final String SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    /** Where a message on a subscriptions/listen stream names the stream, in its _meta. */
    static final String SUBSCRIPTION_ID = "io.modelcontextprotocol/subscriptionId";

    /**
     * The calls that run at once; more wait their turn. A fixed number, not one per processor: the toolchain
     * reads nothing about the machine it runs on (see ArchitectureTest).
     */
    static final int WORKERS = 4;

    /** How many calls may wait for a worker unless --max-queued says otherwise. */
    static final int MAX_QUEUED = 256;

    /**
     * What {@link #handle} returns when it started a call: the answer goes to the {@code later} it was given
     * once the call ends.
     */
    static final Map<String, Object> PENDING = java.util.Collections.unmodifiableMap(new java.util.HashMap<>());

    /** Told when each tool call starts and ends, e.g. to log it or count it (see HttpTransport). */
    interface Observer {
        /** A call of {@code tool} starts; what is returned comes back to {@link #ended}. */
        Object started(String tool);

        /** The call ended with {@code report}, as `alang call` prints it; null when it was cancelled. */
        void ended(Object token, String tool, Map<String, Object> report);
    }

    private static final Observer NO_OBSERVER = new Observer() {
        @Override
        public Object started(String tool) {
            return null;
        }

        @Override
        public void ended(Object token, String tool, Map<String, Object> report) { }
    };

    /**
     * One client's state: the calls it has waiting or running, by the {@link #key} of their request id, so that
     * a cancellation can stop them and ids of two clients never clash; whether it sent notifications/initialized;
     * its subscriptions/listen streams; and where notifications to it go (null: none can be sent, so it is not
     * told of changed tools). Over HTTP, each modern request is a session of its own, whose tasks are the ones
     * every modern request shares.
     */
    static final class Session {
        final Map<Object, Meter> calls = new ConcurrentHashMap<>();
        /** The calls that became tasks; their requests have been answered, so they are not among {@link #calls}. */
        final Tasks tasks;
        /** Whether {@link #tasks} is this session's alone, and ends with it. */
        private final boolean ownsTasks;
        final Map<Object, Subscription> subscriptions = new ConcurrentHashMap<>();
        volatile boolean initialized;
        /** Whether initialize said the client takes tasks; a request can also say so in its _meta. */
        volatile boolean offersTasks;
        final Consumer<Map<String, Object>> notify;

        Session(Consumer<Map<String, Object>> notify, Tasks tasks, boolean ownsTasks) {
            this.notify = notify;
            this.tasks = tasks;
            this.ownsTasks = ownsTasks;
        }

        /** Stops every call and task of this session, e.g. when it ends; their answers are not sent. */
        void cancelAll(String why) {
            for (Meter m : calls.values()) m.stop("cancelled", why);
            if (ownsTasks) tasks.stopAll(why);
        }

        /** Whether a call or task of this session is still waiting or running. */
        boolean busy() {
            return !calls.isEmpty() || tasks.running();
        }
    }

    /**
     * A subscriptions/listen stream: the change notifications it asked for and the server agreed to, sent with
     * {@code notify} until it ends. It is the request {@code id}, which stays unanswered until the server ends
     * the stream ({@link #close}); a client that cancels it or goes away gets nothing more. Guarded by itself, so
     * that its acknowledgment comes before any notification.
     */
    static final class Subscription {
        final Object id;
        final boolean tools;
        final boolean resources;
        private final Consumer<Map<String, Object>> notify;
        private final Consumer<Map<String, Object>> answer;
        private boolean open = true;

        Subscription(Object id, boolean tools, boolean resources, Consumer<Map<String, Object>> notify,
                     Consumer<Map<String, Object>> answer) {
            this.id = id;
            this.tools = tools;
            this.resources = resources;
            this.notify = notify;
            this.answer = answer;
        }

        /** Sends the notification {@code method}, with {@code params} and the stream's id, unless it ended. */
        synchronized void send(String method, Map<String, Object> params) {
            if (!open) return;
            Map<String, Object> p = Json.obj("_meta", Json.obj(SUBSCRIPTION_ID, id));
            p.putAll(params);
            notify.accept(Json.obj("jsonrpc", "2.0", "method", method, "params", p));
        }

        /** Ends the stream; the server ending it answers its request, so the client knows it ended cleanly. */
        synchronized void close(boolean answered) {
            if (!open) return;
            open = false;
            if (answered) {
                answer.accept(Json.obj("jsonrpc", "2.0", "id", id, "result", Json.obj("resultType", "complete",
                    "_meta", Json.obj(SUBSCRIPTION_ID, id, SERVER_INFO, serverInfo()))));
            }
        }
    }

    /** A call under way: whether it has ended, and the task it became, if it did; guarded by itself. */
    private static final class Run {
        boolean done;
        Tasks.Task task;
    }

    private final long maxSteps;
    private final Watchdog watchdog;
    private final int workers;
    private final int maxQueued;
    private volatile Observer observer = NO_OBSERVER;
    /** Every session that has not ended, and the ones among them that get notifications/tools/list_changed. */
    private final java.util.Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final List<Session> listeners = new CopyOnWriteArrayList<>();
    /** Every open subscriptions/listen stream. */
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    /** The tasks of the modern requests over HTTP, which are no session's: made by {@link #start}. */
    private volatile Tasks shared;
    /** What names a task of {@link #shared}; null: task-1, task-2 and on. */
    private java.util.function.Supplier<String> taskIds;
    private ThreadPoolExecutor pool;
    private ScheduledExecutorService ticker;
    /** Turns calls that run long into tasks. */
    private ScheduledExecutorService timer;
    /** Set once the input has ended: a call is answered when it ends, as nobody is left to poll a task. */
    private volatile boolean stopping;
    private java.util.function.LongSupplier clock = Tasks.SYSTEM_CLOCK;
    private long taskAfterMillis = TASK_AFTER_MILLIS;
    /** The program served, which a reload replaces, and its main file. */
    private volatile Loaded current;
    /** Whether the client has sent notifications/initialized: notifications/tools/list_changed waits for it. */
    private volatile boolean initialized;
    /** For `serve --watch`, set by {@link #watch}: the program's files, how often to poll them, and the log; else null. */
    private Reloader reloader;
    private java.util.function.Supplier<Host> hosts = () -> Host.NONE;
    /** The skills served, which --watch can replace; null for a server without --skills. */
    private volatile Skills skills;
    /** For `serve --watch --skills`: polls the skills' directories; else null. */
    private Skills.Watcher skillWatcher;
    private long pollMillis;
    private PrintStream log;
    /**
     * A server whose calls each get {@code maxSteps} steps and the time limit of {@code watchdog}, with
     * {@code workers} of them running at once.
     */
    McpServer(Source src, Program program, long maxSteps, Watchdog watchdog, int workers) {
        this(src, program, maxSteps, watchdog, workers, MAX_QUEUED);
    }

    /** As above, and at most {@code maxQueued} calls wait for a worker. */
    McpServer(Source src, Program program, long maxSteps, Watchdog watchdog, int workers, int maxQueued) {
        if (workers < 1 || maxQueued < 1) throw new IllegalArgumentException("workers and maxQueued must be at least 1");
        this.current = new Loaded(src, program);
        this.maxSteps = maxSteps;
        this.watchdog = watchdog;
        this.workers = workers;
        this.maxQueued = maxQueued;
    }

    /** Sets what answers the calls of host fns: a fresh host for each tool call. */
    void hosts(java.util.function.Supplier<Host> h) {
        this.hosts = h;
    }

    /**
     * For tests: calls become tasks after {@code millis} ms, and tasks read time from {@code clock}. Call it
     * before {@link #start}.
     */
    void tasks(long millis, java.util.function.LongSupplier clock) {
        this.taskAfterMillis = millis;
        this.clock = clock;
    }

    /**
     * Names the tasks that modern requests over HTTP share with {@code ids}, e.g. so that one client cannot guess
     * another's. Call it before {@link #start}.
     */
    void taskIds(java.util.function.Supplier<String> ids) {
        this.taskIds = ids;
    }

    /** Tells {@code o} of every call from now on. */
    void observe(Observer o) {
        this.observer = o;
    }

    /** The number of tools of the program served now. */
    int toolCount() {
        return tools(current.program()).size();
    }

    /** Serves {@code s} too, as the MCP Skills extension defines. Call it before {@link #start}. */
    void skills(Skills s) {
        this.skills = s;
    }

    /** The number of skills served now: 0 without --skills. */
    int skillCount() {
        Skills s = skills;
        return s == null ? 0 : s.count();
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

    /** With {@link #watch}, also polls the skills' directories with {@code w}, and serves what they come to hold. */
    void watchSkills(Skills.Watcher w) {
        this.skillWatcher = w;
    }

    /** A request this server cannot answer with a result: a JSON-RPC error, with its data or null. */
    private static final class RpcException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;
        final transient Object data;

        RpcException(int code, String message) {
            this(code, message, null);
        }

        RpcException(int code, String message, Object data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }

    /** Starts the workers, and with {@link #watch} the polling: before the first {@link #handle}. */
    synchronized void start() {
        if (pool != null) throw new IllegalStateException("already started");
        pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(maxQueued), r -> {
            Thread t = new Thread(r, "alang-call");
            t.setDaemon(true);
            return t;
        });
        if (reloader != null) {
            ticker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "alang-watch");
                t.setDaemon(true);
                return t;
            });
            ticker.scheduleWithFixedDelay(this::reload, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "alang-tasks");
            t.setDaemon(true);
            return t;
        });
        shared = new Tasks(clock, taskIds);
    }

    /**
     * Ends every subscriptions/listen stream, answering its request so that the client knows the server ended it,
     * e.g. as it shuts down.
     */
    void endSubscriptions() {
        for (Subscription s : subscriptions) {
            subscriptions.remove(s);
            s.close(true);
        }
        for (Session s : sessions) s.subscriptions.clear();
    }

    /** Stops the tasks that modern requests share, e.g. as the server shuts down and none can be polled again. */
    void stopSharedTasks(String why) {
        if (shared != null) shared.stopAll(why);
    }

    /** Takes no more calls and waits for those still queued or running, whose answers are sent too. */
    synchronized void stop() {
        if (ticker != null) ticker.shutdown();  // no more polls; one under way may finish
        endSubscriptions();
        if (pool == null) return;
        stopping = true;
        pool.shutdown();
        awaitCalls(pool);
        timer.shutdownNow();
    }

    /** A new client; {@code notify}, unless null, sends it a notification, so it hears of changed tools. */
    Session newSession(Consumer<Map<String, Object>> notify) {
        Session s = new Session(notify, new Tasks(clock), true);
        sessions.add(s);
        if (notify != null) listeners.add(s);
        return s;
    }

    /**
     * A session for one modern request over HTTP, whose tasks are those all such requests share; {@code notify}
     * sends the notifications that belong to the request, those of a subscriptions/listen stream. Call it after
     * {@link #start}, and end it with {@link #endSession} once the request is answered or its client went away.
     */
    Session requestSession(Consumer<Map<String, Object>> notify) {
        Session s = new Session(notify, shared, false);
        sessions.add(s);
        return s;
    }

    /** The client is gone: its calls are stopped and it hears nothing more. */
    void endSession(Session s, String why) {
        sessions.remove(s);
        listeners.remove(s);
        for (Subscription sub : s.subscriptions.values()) {
            subscriptions.remove(sub);
            sub.close(false);
        }
        s.subscriptions.clear();
        s.cancelAll(why);
    }

    /**
     * Answers the messages read from {@code in} until it ends, flushing each answer; then waits for the calls
     * still running, whose answers are written too, and returns.
     */
    void serve(InputStream in, PrintStream out) throws IOException {
        Consumer<Map<String, Object>> sender = answer -> {
            if (answer != null) send(out, answer);
        };
        Session session = newSession(sender);
        start();
        try {
            for (byte[] line = readLine(in); line != null; line = readLine(in)) {
                Map<String, Object> answer = handle(session, line, sender);
                if (answer != null && answer != PENDING) send(out, answer);
            }
        } finally {
            stop();
            sessions.remove(session);
            listeners.remove(session);
        }
    }

    /**
     * One poll of the program's files: serves the program they now hold, if they changed and it compiled, and
     * tells an initialized client when that changed its tools. A change to a tool's body alone does not.
     */
    private void reload() {
        reloadProgram();
        if (skillWatcher != null) reloadSkills();
    }

    private void reloadProgram() {
        try {
            Program p = reloader.poll(log);
            if (p == null) return;
            List<Object> before = tools(current.program());
            current = new Loaded(p.source, p);
            List<Object> after = tools(p);
            if (!before.equals(after)) {
                for (Session s : listeners) {
                    if (s.initialized) s.notify.accept(Json.obj("jsonrpc", "2.0", "method", "notifications/tools/list_changed"));
                }
                for (Subscription s : subscriptions) {
                    if (s.tools) s.send("notifications/tools/list_changed", Json.obj());
                }
            }
            // Last, so that whoever reads it can count on the new program and its notification.
            log.print("alang: reloaded " + p.source.path + ": " + after.size() + " tool(s)\n");
        } catch (RuntimeException e) {
            // Caught, as one that escaped would end the polling for the rest of the session.
            log.print("alang: reloading failed: " + e + "\n");
        }
    }

    /** One poll of the skills' directories: serves what they now hold, if it changed and settled, and tells the clients. */
    private void reloadSkills() {
        try {
            Skills now = skillWatcher.poll(skills, log);
            if (now == null) return;
            skills = now;
            for (Session s : listeners) {
                if (s.initialized) s.notify.accept(Json.obj("jsonrpc", "2.0", "method", "notifications/resources/list_changed"));
            }
            for (Subscription s : subscriptions) {
                if (s.resources) s.send("notifications/resources/list_changed", Json.obj());
            }
            log.print("alang: reloaded the skills: " + now.count() + " skill(s)\n");
        } catch (RuntimeException e) {
            log.print("alang: reloading the skills failed: " + e + "\n");
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
    private void awaitCalls(ThreadPoolExecutor pool) {
        boolean interrupted = false;
        while (true) {
            try {
                if (pool.awaitTermination(1, TimeUnit.HOURS)) break;
            } catch (InterruptedException e) {
                interrupted = true;
                for (Session s : sessions) s.cancelAll("the server is shutting down");
                pool.shutdownNow();
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
     * The answer to one message of {@code session}: a result or an error for a request, null for a notification,
     * a response from the client (this server sends no requests), or a blank line. A tools/call request is
     * checked here, its arguments too, and then runs on a worker, which gives its answer to {@code later} when
     * it ends (null if it was cancelled): {@link #PENDING} here. So is a subscriptions/listen request, which
     * {@code later} answers when the server ends the stream.
     *
     * <p>A request whose _meta names a revision ({@link #PROTOCOL_VERSION}) other than a legacy one is modern:
     * served on its own by that revision, which must be one of {@link #MODERN_VERSIONS} (else -32022), with the
     * capabilities its _meta gives (required: else -32602).
     */
    Map<String, Object> handle(Session session, byte[] line, Consumer<Map<String, Object>> later) {
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
        Object params = m.get("params");
        boolean modern = !notification && isModern(params);
        Loaded now = current;
        Object result;
        try {
            if (modern) checkModern(params);
            // MCP forbids reusing an id in a session; one still in use would also leave a cancellation unsure
            // which call it stops.
            if (!notification && (session.calls.containsKey(key(id)) || session.subscriptions.containsKey(key(id)))) {
                throw new RpcException(INVALID_REQUEST, "id " + Json.write(id) + " is in use by a call that has not been answered");
            }
            if (method.equals("tools/call")) {
                FnDecl f = tool(now.program(), params);
                // A notification wants no answer, and a call changes nothing, so there is nothing to do.
                return notification ? null : start(session, id, now.src(), f, params, modern, later);
            }
            if (method.equals("subscriptions/listen")) {
                return notification ? null : listen(session, id, params, later);
            }
            result = switch (method) {
                case "initialize" -> initialize(session, params);
                case "server/discover" -> discover();
                case "notifications/initialized" -> {
                    session.initialized = true;
                    yield Json.obj();
                }
                // Gone from the modern revisions, which keep no connection to check on.
                case "ping" -> {
                    if (modern) throw new RpcException(METHOD_NOT_FOUND, "method not found: ping (removed in " + version(params) + ")");
                    yield Json.obj();
                }
                case "tools/list" -> modern ? cacheable(Json.obj("tools", tools(now.program())), reloader != null)
                    : Json.obj("tools", tools(now.program()));
                case "notifications/cancelled" -> cancel(session, params);
                case "tasks/get" -> task(session, params).view("complete");
                case "resources/list" -> cacheable(Json.obj("resources", skills(method).resources()));
                case "resources/templates/list" -> cacheable(Json.obj("resourceTemplates", List.of()));
                case "resources/read" -> {
                    Map<String, Object> contents = skills(method).read(uri(params, method));
                    if (contents == null) throw new RpcException(INVALID_PARAMS, "unknown resource: " + uri(params, method));
                    yield cacheable(Json.obj("contents", List.of(contents)));
                }
                case "resources/directory/read" -> {
                    List<Object> children = skills(method).directory(uri(params, method));
                    if (children == null) {
                        throw new RpcException(INVALID_PARAMS, "not a directory: " + uri(params, method));
                    }
                    yield cacheable(Json.obj("resources", children));
                }
                case "skills/list" -> cacheable(Json.obj("skills", skills(method).list()));
                case "skills/get" -> {
                    Map<String, Object> skill = skills(method).get(uri(params, method));
                    if (skill == null) throw new RpcException(INVALID_PARAMS, "unknown skill: " + uri(params, method));
                    yield cacheable(Json.obj("skill", skill));
                }
                case "tasks/cancel" -> {
                    Tasks.Task t = task(session, params);
                    t.cancel("the client cancelled the task");
                    yield t.view("complete");
                }
                default -> throw new RpcException(METHOD_NOT_FOUND, "method not found: " + method);
            };
        } catch (RpcException e) {
            return notification ? null : error(id, e.code, e.getMessage(), e.data);
        }
        if (notification) return null;
        return Json.obj("jsonrpc", "2.0", "id", id, "result", modern ? stamp(result) : result);
    }

    /** The revision a request's params name in their _meta, or null. */
    static String version(Object params) {
        return params instanceof Map<?, ?> p && p.get("_meta") instanceof Map<?, ?> meta
            && meta.get(PROTOCOL_VERSION) instanceof String v ? v : null;
    }

    /** Whether a request is modern: its _meta names a revision, and not one that opens with initialize. */
    static boolean isModern(Object params) {
        String v = version(params);
        return v != null && !LEGACY_VERSIONS.contains(v);
    }

    /** The modern request's revision is one this server speaks, and its _meta gives the client's capabilities. */
    private static void checkModern(Object params) {
        String v = version(params);  // not null: the request is modern
        if (!MODERN_VERSIONS.contains(v)) {
            Map<?, ?> e = (Map<?, ?>) unsupportedVersion(null, v).get("error");
            throw new RpcException(UNSUPPORTED_PROTOCOL_VERSION, (String) e.get("message"), e.get("data"));
        }
        Map<?, ?> meta = (Map<?, ?>) ((Map<?, ?>) params).get("_meta");
        if (!(meta.get(CLIENT_CAPABILITIES) instanceof Map<?, ?>)) {
            throw new RpcException(INVALID_PARAMS, "a request of revision " + v + " needs \"" + CLIENT_CAPABILITIES
                + "\" in its params' _meta: an object, {} for none");
        }
    }

    /** The JSON-RPC error that says {@code requested} is no revision this server speaks. */
    static Map<String, Object> unsupportedVersion(Object id, String requested) {
        return error(id, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version: " + requested + "; supported: "
            + String.join(", ", PROTOCOL_VERSIONS), Json.obj("supported", PROTOCOL_VERSIONS, "requested", requested));
    }

    /** {@code result} as a modern result: with "resultType" (complete, unless it says otherwise) and the server's name in _meta. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> stamp(Object result) {
        Map<String, Object> r = Json.obj("resultType", "complete");
        r.putAll((Map<String, Object>) result);
        Map<String, Object> meta = r.get("_meta") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Json.obj();
        meta.put(SERVER_INFO, serverInfo());
        r.put("_meta", meta);
        return r;
    }

    /** The server's name and version. */
    static Map<String, Object> serverInfo() {
        return Json.obj("name", "alang", "version", Main.VERSION);
    }

    /**
     * subscriptions/listen: acknowledges the notifications the client asked for that this server sends (list
     * changes, with --watch; no resource updates), then sends those until the stream ends. Nothing answers the
     * request until the server ends the stream.
     */
    private Map<String, Object> listen(Session session, Object id, Object params, Consumer<Map<String, Object>> later) {
        if (session.notify == null) throw new RpcException(METHOD_NOT_FOUND, "method not found: subscriptions/listen");
        if (!(params instanceof Map<?, ?> p) || !(p.get("notifications") instanceof Map<?, ?> asked)) {
            throw new RpcException(INVALID_PARAMS, "subscriptions/listen needs params with a \"notifications\" object");
        }
        boolean tools = reloader != null && asked.get("toolsListChanged") == Boolean.TRUE;
        boolean resources = reloader != null && skillWatcher != null && asked.get("resourcesListChanged") == Boolean.TRUE;
        Map<String, Object> agreed = Json.obj();
        if (tools) agreed.put("toolsListChanged", true);
        if (resources) agreed.put("resourcesListChanged", true);
        Subscription s = new Subscription(id, tools, resources, session.notify, later);
        synchronized (s) {
            session.subscriptions.put(key(id), s);
            subscriptions.add(s);
            s.send("notifications/subscriptions/acknowledged", Json.obj("notifications", agreed));
        }
        return PENDING;
    }

    /** The skills served; a server without --skills has no such methods. */
    private Skills skills(String method) {
        Skills s = skills;
        if (s == null) throw new RpcException(METHOD_NOT_FOUND, "method not found: " + method);
        return s;
    }

    /** The "uri" string of a request's params; without one the request is -32602. */
    private static String uri(Object params, String method) {
        if (params instanceof Map<?, ?> p && p.get("uri") instanceof String u) return u;
        throw new RpcException(INVALID_PARAMS, method + " needs params with a \"uri\" string");
    }

    /**
     * {@code result} as a CacheableResult of revision 2026-07-28, which the Skills extension is written against too:
     * complete, fresh for {@link #SKILLS_TTL_MILLIS} (0 with --watch, which may change it any time), and private,
     * so that a shared cache never hands what a --token-file guards to a client without the token.
     */
    private Map<String, Object> cacheable(Map<String, Object> result) {
        return cacheable(result, skillWatcher != null);
    }

    /** As above; {@code changing}: --watch may change it any time, so it is stale at once. */
    private static Map<String, Object> cacheable(Map<String, Object> result, boolean changing) {
        Map<String, Object> r = Json.obj("resultType", "complete");
        r.putAll(result);
        r.put("ttlMs", changing ? 0L : SKILLS_TTL_MILLIS);
        r.put("cacheScope", "private");
        return r;
    }

    static Map<String, Object> error(Object id, int code, String message) {
        return error(id, code, message, null);
    }

    /** A JSON-RPC error, with {@code data} unless it is null. */
    static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> e = Json.obj("code", code, "message", message);
        if (data != null) e.put("data", data);
        return Json.obj("jsonrpc", "2.0", "id", id, "error", e);
    }

    /** initialize, of the legacy revisions: fixes the session's revision, the newest legacy one unless it asks for another. */
    private Map<String, Object> initialize(Session session, Object params) {
        Object asked = params instanceof Map<?, ?> p ? p.get("protocolVersion") : null;
        String version = asked instanceof String s && LEGACY_VERSIONS.contains(s) ? s : LEGACY_VERSIONS.get(0);
        if (params instanceof Map<?, ?> p && offersTasks(p.get("capabilities"))) session.offersTasks = true;
        return Json.obj("protocolVersion", version, "capabilities", capabilities(reloader != null && session.notify != null),
            "serverInfo", serverInfo(), "instructions", instructions());
    }

    /**
     * server/discover, of the modern revisions (a dual-era client may also send it first, to learn which era this
     * server speaks): every revision, the capabilities and the instructions. A modern client hears of changes on a
     * subscriptions/listen stream, on either transport.
     */
    private Map<String, Object> discover() {
        return cacheable(Json.obj("supportedVersions", PROTOCOL_VERSIONS, "capabilities", capabilities(reloader != null),
            "instructions", instructions()), reloader != null);
    }

    /** The server's capabilities; {@code changes}: whether it tells the client when its tools (or skills) change. */
    private Map<String, Object> capabilities(boolean changes) {
        Map<String, Object> capabilities = Json.obj("tools", Json.obj("listChanged", changes));
        Map<String, Object> extensions = Json.obj(Tasks.EXTENSION, Json.obj());
        if (skills != null) {
            capabilities.put("resources", Json.obj("listChanged", changes && skillWatcher != null));
            extensions.put(Skills.EXTENSION, Json.obj("directoryRead", true));
        }
        capabilities.put("extensions", extensions);
        return capabilities;
    }

    private String instructions() {
        Skills s = skills;
        StringBuilder instructions = new StringBuilder(INSTRUCTIONS);
        if (current.program().itemsOf(FnDecl.class).stream().anyMatch(f -> f.host)) instructions.append(HOST_INSTRUCTIONS);
        if (s != null && s.count() > 0) {
            List<String> uris = new ArrayList<>(s.uris());
            instructions.append(SKILL_INSTRUCTIONS).append(String.join(", ", uris.subList(0, Math.min(uris.size(), INSTRUCTION_SKILLS))));
            if (uris.size() > INSTRUCTION_SKILLS) instructions.append(", and ").append(uris.size() - INSTRUCTION_SKILLS).append(" more");
            instructions.append('.');
        }
        return instructions.toString();
    }

    /**
     * MCP tool definitions: the same description and input schema as `alang tools`, the result's schema,
     * and annotations that are true of every ALang tool that asks no host: it changes nothing, so it is read-only, not
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
            // A tool that asks the host may do anything the host does: only that it reaches outside is known.
            tool.put("annotations", ToolDefinitions.hostFns(f).isEmpty()
                ? Json.obj("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false)
                : Json.obj("openWorldHint", true));
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
    private Map<String, Object> start(Session session, Object id, Source src, FnDecl f, Object params, boolean modern,
                                      Consumer<Map<String, Object>> later) {
        Map<?, ?> p = (Map<?, ?>) params;
        // "arguments" is optional, and some clients send null for a tool without parameters.
        Object arguments = p.get("arguments") == null ? Json.obj() : p.get("arguments");
        Object[] args;
        Observer o = observer;
        try {
            args = Main.toolArguments(f, arguments);
        } catch (JsonValues.DecodeException e) {
            Map<String, Object> report = Main.badArguments(e.getMessage());
            o.ended(o.started(f.name), f.name, report);
            return Json.obj("jsonrpc", "2.0", "id", id, "result", result(f, report, modern));
        }
        Meter meter = new Meter(maxSteps);
        Object key = key(id);
        session.calls.put(key, meter);
        Object token = o.started(f.name);
        Run run = new Run();
        try {
            pool.execute(() -> {
                // What the client hears if the call fails inside the server, from a bug to a full heap, instead of
                // waiting for an answer that never comes; such an error still ends the worker thread.
                Map<String, Object> answer = error(id, INTERNAL_ERROR, "the server failed to run the call");
                Map<String, Object> report = null;
                try {
                    Watchdog.Alarm alarm = watchdog.watch(meter);
                    try {
                        report = Main.runTool(src, f, args, meter, hosts.get());
                    } finally {
                        alarm.off();
                    }
                    boolean cancelled = report.get("error") instanceof Map<?, ?> e && "cancelled".equals(e.get("code"));
                    answer = cancelled ? null : Json.obj("jsonrpc", "2.0", "id", id, "result", result(f, report, modern));
                } catch (RuntimeException e) {
                    answer = error(id, INTERNAL_ERROR, "the server failed to run the call: " + e);
                } finally {
                    // Before the answer, so that the client can use the id again as soon as it has the answer.
                    session.calls.remove(key, meter);
                    o.ended(token, f.name, report);
                    Tasks.Task task;
                    synchronized (run) {
                        run.done = true;
                        task = run.task;
                    }
                    if (task == null) later.accept(answer);
                    else task.finish(answer);
                }
            });
            if (offersTasks(session, p, modern)) {
                timer.schedule(() -> toTask(session, run, id, key, meter, modern, later), taskAfterMillis, TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            session.calls.remove(key, meter);
            o.ended(token, f.name, Json.obj("ok", false, "error", Json.obj("code", "busy", "message", "server busy")));
            return error(id, SERVER_BUSY, "the server is busy: " + workers + " calls are running and " + maxQueued
                + " are waiting; try again later");
        }
        return PENDING;
    }

    /**
     * Turns a call that is still waiting or running into a task, and answers its request with it. Its id is
     * then free, and tasks/cancel, no longer notifications/cancelled, stops it.
     */
    private void toTask(Session session, Run run, Object id, Object key, Meter meter, boolean modern,
                        Consumer<Map<String, Object>> later) {
        Map<String, Object> answer;
        synchronized (run) {
            if (run.done || stopping) return;
            Long ttl = watchdog.millis() > 0 ? watchdog.millis() + Tasks.KEEP_MILLIS : null;
            run.task = session.tasks.add(meter, ttl);
            Map<String, Object> view = run.task.view("task");
            answer = Json.obj("jsonrpc", "2.0", "id", id, "result", modern ? stamp(view) : view);
        }
        session.calls.remove(key, meter);
        later.accept(answer);
    }

    /**
     * Whether the client takes tasks for this tools/call: it said so in the request's _meta, or, for a legacy
     * request, in initialize. A modern request has only its own _meta to go by.
     */
    private static boolean offersTasks(Session session, Map<?, ?> params, boolean modern) {
        return !modern && session.offersTasks
            || params.get("_meta") instanceof Map<?, ?> meta && offersTasks(meta.get(CLIENT_CAPABILITIES));
    }

    /** Whether {@code capabilities} name the Tasks extension among their extensions. */
    private static boolean offersTasks(Object capabilities) {
        return capabilities instanceof Map<?, ?> c && c.get("extensions") instanceof Map<?, ?> e && e.containsKey(Tasks.EXTENSION);
    }

    /** The task a tasks/get or tasks/cancel names; an unknown or forgotten one is -32602. */
    private static Tasks.Task task(Session session, Object params) {
        if (!(params instanceof Map<?, ?> p) || !(p.get("taskId") instanceof String id)) {
            throw new RpcException(INVALID_PARAMS, "the request needs params with a \"taskId\" string");
        }
        Tasks.Task t = session.tasks.get(id);
        if (t == null) throw new RpcException(INVALID_PARAMS, "unknown task: " + id);
        return t;
    }

    /**
     * What identifies request {@code id} in {@link #calls}: the id itself, but numbers by value, so that a
     * cancellation of 7.0 or 7e0 finds call 7, and 70.0 or 7e1 call 70. Both kinds are stripped of trailing zeros,
     * as BigDecimal.equals compares the scale too: 70 and 7E+1 differ. Without converting to BigInteger, which an
     * id such as 1e999999999 would make huge.
     */
    static Object key(Object id) {
        if (id instanceof BigInteger i) return new BigDecimal(i).stripTrailingZeros();
        if (id instanceof BigDecimal d) return d.stripTrailingZeros();
        return id;
    }

    private static Map<String, Object> result(FnDecl f, Map<String, Object> report, boolean modern) {
        boolean ok = report.get("ok") == Boolean.TRUE;
        Map<String, Object> result = Json.obj("content", List.of(Json.obj("type", "text", "text", Json.write(report))));
        if (ok && !(f.ret instanceof Type.Void)) result.put("structuredContent", Json.obj("result", report.get("result")));
        result.put("isError", !ok);
        return modern ? stamp(result) : result;
    }

    /**
     * notifications/cancelled: stops the call with that requestId, if it is still waiting or running, and
     * leaves it unanswered; or ends the subscriptions/listen stream it opened. One that has ended, or that this
     * server never had, is ignored, as MCP asks.
     */
    private Map<String, Object> cancel(Session session, Object params) {
        if (params instanceof Map<?, ?> p && p.get("requestId") instanceof Object requestId) {
            Meter meter = session.calls.get(key(requestId));
            if (meter != null) meter.stop("cancelled", "the client cancelled the call");
            Subscription s = session.subscriptions.remove(key(requestId));
            if (s != null) {
                subscriptions.remove(s);
                s.close(false);
            }
        }
        return Json.obj();
    }
}
