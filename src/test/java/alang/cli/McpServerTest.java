package alang.cli;

import static org.junit.jupiter.api.Assertions.*;

import alang.check.Checker;
import alang.diag.Source;
import alang.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** `alang serve`: the program's tools over MCP, one JSON-RPC message per line. */
class McpServerTest {
    private static final String PROGRAM = """
        /// Splits a bill evenly; the remainder goes to the first payers.
        tool fn split_bill(total_cents: i64, people: i64) -> list[i64; 4]
            requires people > 0 and people <= 4
        {
            var shares: list[i64; 4] = [];
            for i in 0..people {
                var share: i64 = total_cents / people;
                if i < total_cents % people {
                    share += 1;
                }
                push(mut shares, share);
            }
            return shares;
        }

        /// Greets.
        tool fn hello(name: str[8]) {
            print("hello ", name);
        }

        fn helper() -> i64 {
            return 1;
        }
        """;

    /** PROGRAM and a tool that, with no step budget, runs until it is stopped. */
    private static final String SPIN_PROGRAM = PROGRAM + """

        /// Adds up 0..n.
        tool fn spin(n: i64) -> i64 {
            var s: i64 = 0;
            for i in 0..n {
                s = wrapping_add(s, i);
            }
            return s;
        }

        /// The answer.
        tool fn answer() -> i64 {
            return 42;
        }
        """;

    /** A tools/call request. */
    private static String call(String id, String tool, String arguments) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"tools/call\", \"params\": {\"name\": \"" + tool
            + "\", \"arguments\": " + arguments + "}}";
    }

    /** A call of spin that runs until it is stopped. */
    private static String longCall(String id) {
        return call(id, "spin", "{\"n\": 1000000000000000}");
    }

    /** The lines the server writes for {@code input}, each checked to be one JSON message. */
    private static List<String> serve(String input) throws IOException {
        return serve(input.getBytes(StandardCharsets.UTF_8), 1000);
    }

    private static List<String> serve(byte[] input, long maxSteps) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server(PROGRAM, maxSteps, 0, 4).serve(new ByteArrayInputStream(input), new PrintStream(out, false, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.isEmpty() || text.endsWith("\n"), text);
        List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\n"));
        for (String line : lines) Json.parse(line);
        return lines;
    }

    /** A server for {@code program}: each call gets maxSteps steps and timeoutMillis ms, and {@code workers} run at once. */
    private static McpServer server(String program, long maxSteps, long timeoutMillis, int workers) {
        Frontend.Result r = Frontend.compile(new Source("bill.al", program), Checker.DEFAULT_MAX_STACK);
        assertTrue(r.ok(), () -> r.diags().renderText());
        return new McpServer(r.diags().source(), r.program(), maxSteps, new Watchdog(timeoutMillis), workers);
    }

    /**
     * The answers in the order of the requests in {@code input}: calls run at once and answer when they end,
     * so the order they are written in can differ from run to run.
     */
    private static List<String> inRequestOrder(String input, List<String> answers) {
        List<Object> ids = input.lines().<Object>map(l -> ((Map<?, ?>) Json.parse(l)).get("id")).toList();
        List<String> sorted = new ArrayList<>(answers);
        sorted.sort(Comparator.comparingInt(a -> ids.indexOf(((Map<?, ?>) Json.parse(a)).get("id"))));
        return sorted;
    }

    /** The "code" of the error a tools/call answer's text reports. */
    private static Object errorCode(String answer) {
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(answer)).get("result");
        assertEquals(true, result.get("isError"), answer);
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        return ((Map<?, ?>) ((Map<?, ?>) Json.parse(text)).get("error")).get("code");
    }

    /**
     * A server running on a thread of its own, sent one message at a time, its answers read as they come.
     * Closing it ends its input and checks that it then stops.
     */
    private static final class Session implements AutoCloseable {
        private final PipedOutputStream input = new PipedOutputStream();
        private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
        private final Thread thread;

        Session(McpServer server) throws IOException {
            PipedInputStream in = new PipedInputStream(input, 1 << 16);
            OutputStream lines = new OutputStream() {
                private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                @Override
                public void write(int b) {
                    if (b != '\n') {
                        line.write(b);
                        return;
                    }
                    answers.add(line.toString(StandardCharsets.UTF_8));
                    line.reset();
                }
            };
            thread = new Thread(() -> {
                try {
                    server.serve(in, new PrintStream(lines, true, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    answers.add("serve failed: " + e);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        void send(String message) throws IOException {
            input.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
        }

        /** The next answer, waiting for it up to 5 seconds. */
        String next() throws InterruptedException {
            String answer = answers.poll(5, TimeUnit.SECONDS);
            assertNotNull(answer, "no answer within 5 seconds");
            return answer;
        }

        /** Ends the input, waits up to 5 seconds for the server to stop, and returns the answers it wrote meanwhile. */
        List<String> end() throws IOException, InterruptedException {
            input.close();
            thread.join(5000);
            assertFalse(thread.isAlive(), "the server did not stop within 5 seconds of the end of its input");
            List<String> rest = new ArrayList<>();
            answers.drainTo(rest);
            return rest;
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    /** One message written readably, as the server writes it: one line, the keys in the same order. */
    private static String msg(String json) {
        return Json.write(Json.parse(json));
    }

    @Test
    void aSessionListsAndCallsTheTools() throws IOException {
        String input = """
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}}
            {"jsonrpc": "2.0", "method": "notifications/initialized"}
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "split_bill", "arguments": {"total_cents": 1000, "people": 3}}}
            {"jsonrpc": "2.0", "id": "four", "method": "tools/call", "params": {"name": "hello", "arguments": {"name": "Ada"}}}
            {"jsonrpc": "2.0", "id": 5, "method": "ping"}
            """;
        List<String> out = inRequestOrder(input, serve(input));
        assertEquals(List.of(
            msg("""
                {"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-06-18",
                  "capabilities": {"tools": {"listChanged": false}, "extensions": {"io.modelcontextprotocol/tasks": {}}},
                  "serverInfo": {"name": "alang", "version": "%s"}, "instructions": %s}}
                """.formatted(Main.VERSION, Json.write(McpServer.INSTRUCTIONS))),
            msg("""
                {"jsonrpc": "2.0", "id": 2, "result": {"tools": [
                  {"name": "split_bill",
                   "description": "Splits a bill evenly; the remainder goes to the first payers.\\nRequires: people > 0 and people <= 4\\nReturns list[i64; 4].",
                   "inputSchema": {"type": "object", "properties": {
                       "total_cents": {"type": "integer", "minimum": -9223372036854775808, "maximum": 9223372036854775807},
                       "people": {"type": "integer", "minimum": 1, "maximum": 4}},
                     "required": ["total_cents", "people"], "additionalProperties": false},
                   "outputSchema": {"type": "object", "properties": {"result": {"type": "array",
                       "items": {"type": "integer", "minimum": -9223372036854775808, "maximum": 9223372036854775807}, "maxItems": 4}},
                     "required": ["result"], "additionalProperties": false},
                   "annotations": {"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}},
                  {"name": "hello", "description": "Greets.",
                   "inputSchema": {"type": "object", "properties": {
                       "name": {"type": "string", "maxLength": 8, "description": "UTF-8 text of at most 8 bytes"}},
                     "required": ["name"], "additionalProperties": false},
                   "annotations": {"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}}]}}
                """),
            msg("""
                {"jsonrpc": "2.0", "id": 3, "result": {
                  "content": [{"type": "text", "text": "{\\"ok\\": true, \\"result\\": [334, 333, 333], \\"stdout\\": \\"\\", \\"steps\\": 16}"}],
                  "structuredContent": {"result": [334, 333, 333]}, "isError": false}}
                """),
            msg("""
                {"jsonrpc": "2.0", "id": "four", "result": {
                  "content": [{"type": "text", "text": "{\\"ok\\": true, \\"result\\": null, \\"stdout\\": \\"hello Ada\\\\n\\", \\"steps\\": 1}"}],
                  "isError": false}}
                """),
            msg("{\"jsonrpc\": \"2.0\", \"id\": 5, \"result\": {}}")), out);
    }

    @Test
    void anUnknownProtocolVersionGetsTheNewest() throws IOException {
        for (String asked : new String[] {"\"1999-01-01\"", "7", "null"}) {
            String reply = serve("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {\"protocolVersion\": "
                + asked + "}}\n").get(0);
            assertTrue(reply.contains("\"protocolVersion\": \"" + McpServer.LEGACY_VERSIONS.get(0) + "\""), reply);
        }
    }

    /** A call that fails is a result the model can read and fix, not a protocol error. */
    @Test
    void failedCallsAreErrorResults() throws IOException {
        String input = """
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "split_bill", "arguments": {"total_cents": 10, "people": 0}}}
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "split_bill", "arguments": {"total_cents": "10", "people": 1}}}
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "split_bill"}}
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "split_bill", "arguments": [10, 1]}}
            {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "split_bill", "arguments": {"total_cents": 10, "people": 4}}}
            """;
        List<String> out = inRequestOrder(input, serve(input.getBytes(StandardCharsets.UTF_8), 10));  // four people take more than 10 steps
        String[] codes = {"R0006", "bad_arguments", "bad_arguments", "bad_arguments", "R0011"};
        for (int i = 0; i < codes.length; i++) {
            assertEquals(codes[i], errorCode(out.get(i)), out.get(i));
            Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(out.get(i))).get("result");
            assertEquals(false, result.containsKey("structuredContent"), out.get(i));
        }
        assertEquals(codes.length, out.size(), out.toString());
    }

    /** Each call runs on a thread of its own: a long one holds up neither another call nor a ping. */
    @Test
    void aLongCallHoldsUpNothingElse() throws Exception {
        try (Session s = new Session(server(SPIN_PROGRAM, 0, 0, 2))) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/call\", \"params\": {\"name\": \"spin\", "
                + "\"arguments\": {\"n\": 1000000000000000}}}");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/call\", \"params\": {\"name\": \"split_bill\", "
                + "\"arguments\": {\"total_cents\": 1000, \"people\": 3}}}");
            assertTrue(s.next().contains("\"structuredContent\": {\"result\": [334, 333, 333]}"));
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"ping\"}");
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 3, \"result\": {}}"), s.next());
            // The long call is still running; cancelling it stops it, and it gets no answer.
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": 1}}");
            assertEquals(List.of(), s.end());
        }
    }

    /** A cancellation stops a call that is waiting for a worker too, and one for a call that has ended is ignored. */
    @Test
    void cancelledCallsGetNoAnswer() throws Exception {
        try (Session s = new Session(server(SPIN_PROGRAM, 0, 0, 1))) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": \"a\", \"method\": \"tools/call\", \"params\": {\"name\": \"spin\", "
                + "\"arguments\": {\"n\": 1000000000000000}}}");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": \"b\", \"method\": \"tools/call\", \"params\": {\"name\": \"spin\", "
                + "\"arguments\": {\"n\": 1000000000000000}}}");
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": \"b\"}}");
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": \"a\"}}");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": \"c\", \"method\": \"tools/call\", \"params\": {\"name\": \"spin\", "
                + "\"arguments\": {\"n\": 3}}}");
            assertTrue(s.next().contains("\"id\": \"c\""));
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": \"c\"}}");
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {}}");
            assertEquals(List.of(), s.end());
        }
    }

    /** The _meta of a request that tells the server the client takes tasks, as Claude Code sends it. */
    private static final String TASKS_META = "\"_meta\": {\"io.modelcontextprotocol/clientCapabilities\": "
        + "{\"extensions\": {\"io.modelcontextprotocol/tasks\": {}}}}";

    /** A tools/call request that offers to take a task. */
    private static String taskCall(String id, String tool, String arguments) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"tools/call\", \"params\": {\"name\": \"" + tool
            + "\", \"arguments\": " + arguments + ", " + TASKS_META + "}}";
    }

    /** A tasks/get or tasks/cancel request. */
    private static String taskRequest(String id, String method, String taskId) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": {\"taskId\": \""
            + taskId + "\"}}";
    }

    /** The result of an answer. */
    private static Map<?, ?> resultOf(String answer) {
        Object result = ((Map<?, ?>) Json.parse(answer)).get("result");
        assertNotNull(result, answer);
        return (Map<?, ?>) result;
    }

    /** 2023-11-14T22:13:20Z, a clock that does not move. */
    private static final long NOW = 1_700_000_000_000L;

    /**
     * A client that offers the Tasks extension gets a task for a call that is still running after a while; it
     * asks how the task is with tasks/get and stops it with tasks/cancel.
     */
    @Test
    void aLongCallBecomesATask() throws Exception {
        McpServer server = server(SPIN_PROGRAM, 0, 0, 2);
        server.tasks(50, () -> NOW);
        try (Session s = new Session(server)) {
            s.send(taskCall("1", "spin", "{\"n\": 1000000000000000}"));
            assertEquals(msg("""
                {"jsonrpc": "2.0", "id": 1, "result": {"resultType": "task", "taskId": "task-1", "status": "working",
                  "createdAt": "2023-11-14T22:13:20Z", "lastUpdatedAt": "2023-11-14T22:13:20Z", "ttlMs": null,
                  "pollIntervalMs": 2000}}
                """), s.next());
            // The request is answered, so its id is free again.
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}");
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 1, \"result\": {}}"), s.next());
            s.send(taskRequest("2", "tasks/get", "task-1"));
            assertEquals("working", resultOf(s.next()).get("status"));
            s.send(taskRequest("3", "tasks/cancel", "task-1"));
            assertEquals(msg("""
                {"jsonrpc": "2.0", "id": 3, "result": {"resultType": "complete", "taskId": "task-1", "status": "cancelled",
                  "statusMessage": "the client cancelled the task", "createdAt": "2023-11-14T22:13:20Z",
                  "lastUpdatedAt": "2023-11-14T22:13:20Z", "ttlMs": null}}
                """), s.next());
            s.send(taskRequest("4", "tasks/get", "task-1"));
            assertEquals("cancelled", resultOf(s.next()).get("status"));
            s.send(taskRequest("5", "tasks/get", "task-9"));
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 5, \"error\": {\"code\": -32602, \"message\": \"unknown task: task-9\"}}"),
                s.next());
            assertEquals(List.of(), s.end());
        }
    }

    /**
     * A task's result is the tools/call result the call would have been answered with, and its ttlMs keeps it an
     * hour past the call's time limit; a call that ends in time is answered as usual, task or not.
     */
    @Test
    void aTaskEndsWithTheCallsResult() throws Exception {
        McpServer server = server(SPIN_PROGRAM, 0, 60_000, 1);
        server.tasks(50, () -> NOW);
        try (Session s = new Session(server)) {
            // Offered no task, the long call holds the only worker; the next call waits for it, and becomes a task.
            s.send(longCall("1"));
            s.send(taskCall("2", "answer", "{}"));
            Map<?, ?> task = resultOf(s.next());
            assertEquals("task", task.get("resultType"), task.toString());
            assertEquals(new java.math.BigInteger("3660000"), task.get("ttlMs"));
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": 1}}");
            // The one worker takes the calls in turn, so this one is answered only after the task's call ended.
            s.send(call("3", "answer", "{}"));
            assertTrue(s.next().contains("\"id\": 3"));
            s.send(taskRequest("3", "tasks/get", "task-1"));
            Map<?, ?> done = resultOf(s.next());
            assertEquals(msg("""
                {"resultType": "complete", "taskId": "task-1", "status": "completed", "createdAt": "2023-11-14T22:13:20Z",
                  "lastUpdatedAt": "2023-11-14T22:13:20Z", "ttlMs": 3660000,
                  "result": {"content": [{"type": "text", "text": "{\\"ok\\": true, \\"result\\": 42, \\"stdout\\": \\"\\", \\"steps\\": 1}"}],
                    "structuredContent": {"result": 42}, "isError": false}}
                """), Json.write(done));
            // A quick call is answered with its result, though the client takes tasks.
            s.send(taskCall("4", "answer", "{}"));
            assertTrue(s.next().contains("\"structuredContent\": {\"result\": 42}"));
            assertEquals(List.of(), s.end());
        }
    }

    /** A client that says in initialize that it takes tasks gets them without saying so again in each call. */
    @Test
    void initializeCanOfferTasks() throws Exception {
        McpServer server = server(SPIN_PROGRAM, 0, 0, 1);
        server.tasks(50, () -> NOW);
        try (Session s = new Session(server)) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 0, \"method\": \"initialize\", \"params\": {\"protocolVersion\": \"2025-11-25\", "
                + "\"capabilities\": {\"extensions\": {\"io.modelcontextprotocol/tasks\": {}}}}}");
            s.next();
            s.send(longCall("1"));
            assertEquals("task-1", resultOf(s.next()).get("taskId"));
            s.send(taskRequest("2", "tasks/cancel", "task-1"));
            assertEquals("cancelled", resultOf(s.next()).get("status"));
            assertEquals(List.of(), s.end());
        }
    }

    /** Forgotten: a finished task an hour after it ended. */
    @Test
    void finishedTasksAreForgottenAfterAnHour() {
        long[] now = {NOW};
        Tasks tasks = new Tasks(() -> now[0]);
        Tasks.Task t = tasks.add(new alang.interp.Meter(0), null);
        t.finish(Json.obj("jsonrpc", "2.0", "id", 1, "result", Json.obj("isError", false)));
        now[0] += Tasks.KEEP_MILLIS - 1;
        assertSame(t, tasks.get(t.id));
        now[0] += 1;
        assertNull(tasks.get(t.id));
        // A running task is kept however long it runs.
        Tasks.Task running = tasks.add(new alang.interp.Meter(0), 1L);
        now[0] += 10 * Tasks.KEEP_MILLIS;
        assertSame(running, tasks.get(running.id));
        assertEquals("task-2", running.id);
    }

    /**
     * Arguments that do not fit are answered at once, not after the calls ahead of them; null arguments are
     * none; and a cancellation finds a call by the value of its id, whatever way the number is written.
     */
    @Test
    void badArgumentsDoNotWaitForAWorker() throws Exception {
        try (Session s = new Session(server(SPIN_PROGRAM, 0, 0, 1))) {
            s.send(longCall("1"));
            s.send(call("2", "split_bill", "{\"total_cents\": 10, \"people\": \"x\"}"));
            String answer = s.next();
            assertTrue(answer.contains("\"id\": 2"), answer);
            assertEquals("bad_arguments", errorCode(answer), answer);
            s.send(call("3", "split_bill", "null"));
            answer = s.next();
            assertEquals("bad_arguments", errorCode(answer), answer);
            assertTrue(answer.contains("total_cents: missing argument"), answer);
            // A call whose arguments fit waits for the worker, which the long call holds until it is cancelled.
            s.send(call("4", "answer", "null"));
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": 1.0}}");
            answer = s.next();
            assertTrue(answer.contains("\"id\": 4") && answer.contains("\"structuredContent\": {\"result\": 42}"), answer);
            assertEquals(List.of(), s.end());
        }
    }

    /** An id still in use by a call is refused, so that a cancellation cannot stop the wrong call. */
    @Test
    void anIdInUseIsRefused() throws Exception {
        try (Session s = new Session(server(SPIN_PROGRAM, 0, 0, 2))) {
            s.send(longCall("7"));
            s.send(call("7", "answer", "{}"));
            String inUse = msg("{\"jsonrpc\": \"2.0\", \"id\": 7, \"error\": {\"code\": -32600, "
                + "\"message\": \"id 7 is in use by a call that has not been answered\"}}");
            assertEquals(inUse, s.next());
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 7, \"method\": \"ping\"}");
            assertEquals(inUse, s.next());
            s.send("{\"jsonrpc\": \"2.0\", \"id\": \"7\", \"method\": \"ping\"}");
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": \"7\", \"result\": {}}"), s.next());
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": 70e-1}}");
            assertEquals(List.of(), s.end());
        }
    }

    /**
     * A number id with trailing zeros is the same id however it is written: 70.0 and 7e1 are 70, which a key that
     * kept BigDecimal's scale took for another id.
     */
    @Test
    void anIdEndingInZeroIsFoundWrittenAnyWay() throws Exception {
        try (Session s = new Session(server(SPIN_PROGRAM, 0, 0, 2))) {
            s.send(longCall("70"));
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 70.0, \"method\": \"ping\"}");
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 70.0, \"error\": {\"code\": -32600, "
                + "\"message\": \"id 70.0 is in use by a call that has not been answered\"}}"), s.next());
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": 7e1}}");
            assertEquals(List.of(), s.end());
        }
    }

    /** A call that runs past --timeout is stopped, and its answer says so, whatever its step budget. */
    @Test
    void aCallPastItsTimeLimitIsAnErrorResult() throws Exception {
        try (Session s = new Session(server(SPIN_PROGRAM, 0, 200, 2))) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/call\", \"params\": {\"name\": \"spin\", "
                + "\"arguments\": {\"n\": 1000000000000000}}}");
            String answer = s.next();
            assertEquals("timeout", errorCode(answer), answer);
            assertTrue(answer.contains("time limit of 0.2 s (--timeout)"), answer);
            assertEquals(List.of(), s.end());
        }
    }

    @Test
    void protocolErrors() throws IOException {
        List<String> out = serve("""
            {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "nope"}}
            {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "helper"}}
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {}}
            {"jsonrpc": "2.0", "id": 4, "method": "resources/list"}
            {"id": 5, "method": "ping"}
            {"jsonrpc": "2.0", "id": {"a": 1}, "method": "ping"}
            {"jsonrpc": "2.0", "id": 7}
            [1, 2]
            not json
            """);
        assertEquals(List.of(
            msg("{\"jsonrpc\": \"2.0\", \"id\": 1, \"error\": {\"code\": -32602, \"message\": \"unknown tool: nope\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": 2, \"error\": {\"code\": -32602, \"message\": \"unknown tool: helper\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": 3, \"error\": {\"code\": -32602, \"message\": \"tools/call needs params with a \\\"name\\\" string\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": 4, \"error\": {\"code\": -32601, \"message\": \"method not found: resources/list\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": 5, \"error\": {\"code\": -32600, \"message\": \"\\\"jsonrpc\\\" must be \\\"2.0\\\"\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": null, \"error\": {\"code\": -32600, \"message\": \"an id must be a string or a number\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": 7, \"error\": {\"code\": -32600, \"message\": \"a request needs a \\\"method\\\" string\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": null, \"error\": {\"code\": -32600, \"message\": \"a message must be a JSON object\"}}"),
            msg("{\"jsonrpc\": \"2.0\", \"id\": null, \"error\": {\"code\": -32700, \"message\": \"the message is not valid JSON: invalid literal at offset 0\"}}")),
            out);
    }

    /** Notifications, responses and blank lines get no answer; "\r\n" ends a line too. */
    @Test
    void someMessagesGetNoAnswer() throws IOException {
        assertEquals(List.of(), serve("""
            {"jsonrpc": "2.0", "method": "notifications/initialized"}
            {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 1}}
            {"jsonrpc": "2.0", "method": "tools/call", "params": {"name": "nope"}}
            {"jsonrpc": "2.0", "id": 9, "result": {}}

            """));
        assertEquals(List.of(msg("{\"jsonrpc\": \"2.0\", \"id\": 1, \"result\": {}}")),
            serve("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}\r\n"));
        // The last message needs no newline.
        assertEquals(List.of(msg("{\"jsonrpc\": \"2.0\", \"id\": 2, \"result\": {}}")),
            serve("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"ping\"}"));
    }

    /** A NUL byte is part of its line, not the end of it or of the input. */
    @Test
    void aNulByteDoesNotEndALine() throws IOException {
        byte[] input = "\u0000\n{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}\n".getBytes(StandardCharsets.UTF_8);
        List<String> out = serve(input, 1000);
        assertEquals(2, out.size(), out.toString());
        assertTrue(out.get(0).contains("\"code\": -32700"), out.get(0));
        assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 1, \"result\": {}}"), out.get(1));
    }

    @Test
    void aMessageThatIsNotUtf8IsAParseError() throws IOException {
        byte[] bad = {'{', '"', 'a', '"', ':', '"', (byte) 0xFF, '"', '}', '\n'};
        assertEquals(List.of(msg("{\"jsonrpc\": \"2.0\", \"id\": null, \"error\": {\"code\": -32700, "
            + "\"message\": \"the message is not valid UTF-8\"}}")), serve(bad, 1000));
    }

    // ------------------------------------------------------------------ serve --watch

    private static final String LIST_CHANGED = msg("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/tools/list_changed\"}");

    /** Replaces a file's text at once, as an editor saving it whole does, so a poll never sees it half written. */
    private static void save(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** What a server logs, one line at a time, for a test to wait on. */
    private static final class Log extends OutputStream {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        @Override
        public synchronized void write(int b) {
            if (b != '\n') {
                line.write(b);
                return;
            }
            lines.add(line.toString(StandardCharsets.UTF_8));
            line.reset();
        }

        /** Waits up to 5 seconds for a line that contains {@code text}, passing over the lines before it. */
        void await(String text) throws InterruptedException {
            List<String> passed = new ArrayList<>();
            while (true) {
                String next = lines.poll(5, TimeUnit.SECONDS);
                assertNotNull(next, () -> "no line containing \"" + text + "\" within 5 seconds; passed over " + passed);
                if (next.contains(text)) return;
                passed.add(next);
            }
        }
    }

    /** A server for the program in {@code main} that polls its files every 20 ms, logging to {@code log}. */
    private static McpServer watching(Path main, Log log) throws IOException {
        Frontend.Result r = Frontend.compile(new Source(main.toString(), Files.readString(main)), Checker.DEFAULT_MAX_STACK);
        assertTrue(r.ok(), () -> r.diags().renderText());
        McpServer server = new McpServer(r.program().source, r.program(), 0, new Watchdog(0), 2);
        server.watch(new Reloader(r.program(), Checker.DEFAULT_MAX_STACK), 20, new PrintStream(log, true, StandardCharsets.UTF_8));
        return server;
    }

    private static String tool(String name, int value) {
        return "/// A number.\ntool fn " + name + "() -> i64 {\n    return " + value + ";\n}\n";
    }

    /**
     * Each version of the files that compiles is served; the client hears when the tools change, not when
     * only a body does; a version with errors is logged and the one before it kept; and an import that does
     * not exist yet is watched too.
     */
    @Test
    void watchServesEachVersionThatCompiles(@TempDir Path dir) throws Exception {
        Path main = dir.resolve("main.al");
        save(main, tool("one", 1));
        Log log = new Log();
        try (Session s = new Session(watching(main, log))) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {}}");
            assertTrue(s.next().contains("\"listChanged\": true"));
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}");

            save(main, tool("one", 1) + tool("two", 2));
            assertEquals(LIST_CHANGED, s.next());
            log.await("reloaded");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/list\"}");
            assertTrue(s.next().contains("\"name\": \"two\""));

            // A new body only: served, but the tools the client knows have not changed.
            save(main, tool("one", 1) + tool("two", 3));
            log.await("reloaded");
            s.send(call("3", "two", "{}"));
            assertTrue(s.next().contains("\"structuredContent\": {\"result\": 3}"));

            // Errors: logged, and the version before stays served.
            save(main, tool("one", 1) + "fn broken() -> i64 {\n    return true;\n}\n");
            log.await("main.al has 1 error(s); still serving the program loaded before");
            s.send(call("4", "two", "{}"));
            assertTrue(s.next().contains("\"structuredContent\": {\"result\": 3}"));

            // An import that does not exist yet fails, and is served once it is written. (Its own tools would
            // stay its own; the importer's tool uses its function.)
            String fromLib = "/// Calls the library.\ntool fn from_lib() -> i64 {\n    return three();\n}\n";
            save(main, "import \"lib.al\";\n\n" + tool("one", 1) + fromLib);
            log.await("still serving the program loaded before");
            save(dir.resolve("lib.al"), "fn three() -> i64 {\n    return 3;\n}\n");
            assertEquals(LIST_CHANGED, s.next());
            log.await("reloaded");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 5, \"method\": \"tools/list\"}");
            String tools = s.next();
            assertTrue(tools.contains("\"name\": \"from_lib\"") && !tools.contains("\"name\": \"two\""), tools);

            // An imported file is watched like the main one.
            save(dir.resolve("lib.al"), "fn three() -> i64 {\n    return 4;\n}\n");
            log.await("reloaded");
            s.send(call("6", "from_lib", "{}"));
            assertTrue(s.next().contains("\"structuredContent\": {\"result\": 4}"));
            assertEquals(List.of(), s.end());
        }
    }

    /** With --watch the skills are polled too: a change is served, and told, once it has settled. */
    @Test
    void watchServesChangedSkills(@TempDir Path dir) throws Exception {
        Path main = dir.resolve("main.al");
        save(main, tool("one", 1));
        Path skill = dir.resolve("skills/counting/SKILL.md");
        Files.createDirectories(skill.getParent());
        save(skill, "---\nname: counting\ndescription: Count.\n---\n");
        List<String> dirs = List.of(dir.resolve("skills").toString());
        Log log = new Log();
        McpServer server = watching(main, log);
        server.skills(Skills.load(dirs));
        server.watchSkills(new Skills.Watcher(dirs));
        try (Session s = new Session(server)) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {}}");
            assertTrue(s.next().contains("\"resources\": {\"listChanged\": true}"));
            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}");
            save(skill, "---\nname: counting\ndescription: Count to two.\n---\n");
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/resources/list_changed\"}"), s.next());
            log.await("reloaded the skills: 1 skill(s)");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"skills/list\"}");
            String list = s.next();
            assertTrue(list.contains("\"description\": \"Count to two.\"") && list.contains("\"ttlMs\": 0"), list);

            save(skill, "---\nname: counted\ndescription: Count.\n---\n");
            log.await("--skills: ");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"skills/list\"}");
            assertTrue(s.next().contains("\"description\": \"Count to two.\""));
            assertEquals(List.of(), s.end());
        }
    }

    /** Without --watch the tools never change, and the server says so. */
    @Test
    void withoutWatchTheToolsDoNotChange() throws IOException {
        String reply = serve("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {}}\n").get(0);
        assertTrue(reply.contains("\"listChanged\": false"), reply);
    }

    /** The client hears of changed tools only once it has said it is initialized, as MCP asks. */
    @Test
    void listChangedWaitsForInitialized(@TempDir Path dir) throws Exception {
        Path main = dir.resolve("main.al");
        save(main, tool("one", 1));
        Log log = new Log();
        try (Session s = new Session(watching(main, log))) {
            save(main, tool("two", 2));
            log.await("reloaded");
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}");
            assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 1, \"result\": {}}"), s.next());
            s.send(call("2", "two", "{}"));
            assertTrue(s.next().contains("\"structuredContent\": {\"result\": 2}"));
            assertEquals(List.of(), s.end());
        }
    }

    @Test
    void aToolThatAsksTheHostIsNotReadOnly() throws IOException {
        String program = """
            host fn stock(sku: str[8]) -> i64;

            /// Whether an item is in stock.
            tool fn in_stock(sku: str[8]) -> bool {
                return stock(sku) > 0;
            }

            /// Pure.
            tool fn twice(n: i64) -> i64 {
                return n * 2;
            }
            """;
        McpServer server = server(program, 1000, 0, 1);
        server.hosts(() -> (fn, args) -> java.math.BigInteger.valueOf(3));
        String input = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {}}\n"
            + "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/list\"}\n"
            + call("3", "in_stock", "{\"sku\": \"tea\"}") + "\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, false, StandardCharsets.UTF_8));
        List<String> lines = List.of(out.toString(StandardCharsets.UTF_8).split("\n"));
        Map<?, ?> init = (Map<?, ?>) ((Map<?, ?>) Json.parse(lines.get(0))).get("result");
        assertEquals(McpServer.INSTRUCTIONS + McpServer.HOST_INSTRUCTIONS, init.get("instructions"));
        List<?> tools = (List<?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(lines.get(1))).get("result")).get("tools");
        assertEquals("{\"openWorldHint\": true}", Json.write(((Map<?, ?>) tools.get(0)).get("annotations")));
        assertEquals("{\"readOnlyHint\": true, \"destructiveHint\": false, \"idempotentHint\": true, \"openWorldHint\": false}",
            Json.write(((Map<?, ?>) tools.get(1)).get("annotations")));
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(lines.get(2))).get("result");
        assertEquals("{\"result\": true}", Json.write(result.get("structuredContent")));
        String text = (String) ((Map<?, ?>) ((List<?>) result.get("content")).get(0)).get("text");
        assertTrue(text.contains("\"host_calls\": [{\"fn\": \"stock\", \"args\": {\"sku\": \"tea\"}, \"result\": 3}]"), text);
    }

    // ---------------------------------------------------------------- the modern revision (2026-07-28)

    /** What a modern request carries in its params' _meta: its revision and the client's capabilities. */
    private static final String MODERN_META = "\"_meta\": {\"io.modelcontextprotocol/protocolVersion\": \"2026-07-28\", "
        + "\"io.modelcontextprotocol/clientInfo\": {\"name\": \"test\", \"version\": \"1\"}, "
        + "\"io.modelcontextprotocol/clientCapabilities\": {}}";

    /** A modern request; {@code params} without braces, the _meta added. */
    private static String modern(String id, String method, String params) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": {"
            + (params.isEmpty() ? "" : params + ", ") + MODERN_META + "}}";
    }

    /** What a modern result carries in its _meta: the server's name. */
    private static final String SERVER_META = "\"_meta\": {\"io.modelcontextprotocol/serverInfo\": {\"name\": \"alang\", "
        + "\"version\": \"" + Main.VERSION + "\"}}";

    /**
     * A modern client sends no initialize: each request names its revision and capabilities, and each result says
     * what it is and who answered. server/discover says what the server speaks.
     */
    @Test
    void modernRequestsStandAlone() throws IOException {
        String input = modern("1", "server/discover", "") + "\n"
            + modern("2", "tools/list", "") + "\n"
            + modern("3", "tools/call", "\"name\": \"split_bill\", \"arguments\": {\"total_cents\": 10, \"people\": 2}") + "\n"
            + modern("4", "tools/call", "\"name\": \"split_bill\", \"arguments\": {\"total_cents\": 10, \"people\": 0}") + "\n";
        List<String> out = inRequestOrder(input, serve(input));
        assertEquals(msg("""
            {"jsonrpc": "2.0", "id": 1, "result": {"resultType": "complete",
              "supportedVersions": ["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"],
              "capabilities": {"tools": {"listChanged": false}, "extensions": {"io.modelcontextprotocol/tasks": {}}},
              "instructions": %s, "ttlMs": 300000, "cacheScope": "private", %s}}
            """.formatted(Json.write(McpServer.INSTRUCTIONS), SERVER_META)), out.get(0));
        Map<?, ?> list = resultOf(out.get(1));
        assertEquals(List.of("resultType", "tools", "ttlMs", "cacheScope", "_meta"), new ArrayList<>(list.keySet()));
        assertEquals(2, ((List<?>) list.get("tools")).size());
        assertEquals(msg("""
            {"jsonrpc": "2.0", "id": 3, "result": {"resultType": "complete",
              "content": [{"type": "text", "text": "{\\"ok\\": true, \\"result\\": [5, 5], \\"stdout\\": \\"\\", \\"steps\\": 11}"}],
              "structuredContent": {"result": [5, 5]}, "isError": false, %s}}
            """.formatted(SERVER_META)), out.get(2));
        Map<?, ?> failed = resultOf(out.get(3));
        assertEquals(true, failed.get("isError"));
        assertEquals("complete", failed.get("resultType"));
    }

    /**
     * A modern request names a revision the server speaks (-32022, with the ones it does) and the client's
     * capabilities (-32602); ping is gone from it. initialize stays legacy, whatever revision it asks for.
     */
    @Test
    void modernRequestsAreChecked() throws IOException {
        String input = modern("1", "tools/list", "").replace("2026-07-28", "2099-01-01") + "\n"
            + "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/list\", \"params\": {\"_meta\": "
            + "{\"io.modelcontextprotocol/protocolVersion\": \"2026-07-28\"}}}\n"
            + modern("3", "ping", "") + "\n"
            + "{\"jsonrpc\": \"2.0\", \"id\": 4, \"method\": \"initialize\", \"params\": {\"protocolVersion\": \"2026-07-28\"}}\n"
            // A legacy revision named in _meta is no modern request: served as before.
            + modern("5", "ping", "").replace("2026-07-28", "2025-11-25") + "\n"
            + "{\"jsonrpc\": \"2.0\", \"id\": 6, \"method\": \"server/discover\"}\n";
        List<String> out = serve(input);
        assertEquals(msg("""
            {"jsonrpc": "2.0", "id": 1, "error": {"code": -32022,
              "message": "Unsupported protocol version: 2099-01-01; supported: 2026-07-28, 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05",
              "data": {"supported": ["2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"], "requested": "2099-01-01"}}}
            """), out.get(0));
        assertEquals(msg("""
            {"jsonrpc": "2.0", "id": 2, "error": {"code": -32602, "message":
              "a request of revision 2026-07-28 needs \\"io.modelcontextprotocol/clientCapabilities\\" in its params' _meta: an object, {} for none"}}
            """), out.get(1));
        assertEquals(msg("""
            {"jsonrpc": "2.0", "id": 3, "error": {"code": -32601, "message": "method not found: ping (removed in 2026-07-28)"}}
            """), out.get(2));
        assertEquals("2025-11-25", resultOf(out.get(3)).get("protocolVersion"));
        assertEquals(msg("{\"jsonrpc\": \"2.0\", \"id\": 5, \"result\": {}}"), out.get(4));
        assertEquals("complete", resultOf(out.get(5)).get("resultType"));
    }

    /** A modern request offers to take a task in its own _meta; what initialize said does not count for it. */
    @Test
    void aModernCallBecomesATask() throws Exception {
        McpServer server = server(SPIN_PROGRAM, 0, 0, 2);
        server.tasks(50, () -> NOW);
        try (Session s = new Session(server)) {
            s.send("{\"jsonrpc\": \"2.0\", \"id\": 0, \"method\": \"initialize\", \"params\": {\"protocolVersion\": \"2025-11-25\", "
                + "\"capabilities\": {\"extensions\": {\"io.modelcontextprotocol/tasks\": {}}}}}");
            s.next();
            s.send(modern("1", "tools/call", "\"name\": \"answer\", \"arguments\": {}"));
            assertEquals("complete", resultOf(s.next()).get("resultType"));
            s.send(modern("2", "tools/call", "\"name\": \"spin\", \"arguments\": {\"n\": 1000000000000000}").replace(
                "\"io.modelcontextprotocol/clientCapabilities\": {}",
                "\"io.modelcontextprotocol/clientCapabilities\": {\"extensions\": {\"io.modelcontextprotocol/tasks\": {}}}"));
            assertEquals(msg("""
                {"jsonrpc": "2.0", "id": 2, "result": {"resultType": "task", "taskId": "task-1", "status": "working",
                  "createdAt": "2023-11-14T22:13:20Z", "lastUpdatedAt": "2023-11-14T22:13:20Z", "ttlMs": null,
                  "pollIntervalMs": 2000, %s}}
                """.formatted(SERVER_META)), s.next());
            s.send(modern("3", "tasks/cancel", "\"taskId\": \"task-1\""));
            Map<?, ?> cancelled = resultOf(s.next());
            assertEquals("cancelled", cancelled.get("status"));
            assertTrue(cancelled.containsKey("_meta"), cancelled.toString());
            assertEquals(List.of(), s.end());
        }
    }

    /**
     * subscriptions/listen acknowledges what the server will send (list changes, with --watch) and then sends it,
     * each notification naming the stream; cancelling the stream ends it, and the server ending it answers it.
     */
    @Test
    void subscriptionsListen(@TempDir Path dir) throws Exception {
        Path main = dir.resolve("main.al");
        save(main, tool("one", 1));
        Log log = new Log();
        try (Session s = new Session(watching(main, log))) {
            s.send(modern("1", "server/discover", ""));
            assertTrue(s.next().contains("\"tools\": {\"listChanged\": true}"));
            s.send(modern("\"tools\"", "subscriptions/listen",
                "\"notifications\": {\"toolsListChanged\": true, \"promptsListChanged\": true, \"resourceSubscriptions\": [\"skill://x\"]}"));
            assertEquals(msg("""
                {"jsonrpc": "2.0", "method": "notifications/subscriptions/acknowledged",
                  "params": {"_meta": {"io.modelcontextprotocol/subscriptionId": "tools"}, "notifications": {"toolsListChanged": true}}}
                """), s.next());
            s.send(modern("\"other\"", "subscriptions/listen", "\"notifications\": {}"));
            assertTrue(s.next().contains("\"io.modelcontextprotocol/subscriptionId\": \"other\"}, \"notifications\": {}"));
            // The stream's id is in use while it is open.
            s.send(modern("\"tools\"", "tools/list", ""));
            assertTrue(s.next().contains("\"code\": -32600"));

            save(main, tool("one", 1) + tool("two", 2));
            assertEquals(msg("""
                {"jsonrpc": "2.0", "method": "notifications/tools/list_changed",
                  "params": {"_meta": {"io.modelcontextprotocol/subscriptionId": "tools"}}}
                """), s.next());
            log.await("reloaded");

            s.send("{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": \"tools\"}}");
            s.send(modern("2", "tools/list", ""));
            assertTrue(s.next().contains("\"id\": 2"));
            save(main, tool("three", 3));
            log.await("reloaded");
            // Nothing was sent for the cancelled stream; the one left open is answered as the server stops.
            assertEquals(List.of(msg("""
                {"jsonrpc": "2.0", "id": "other", "result": {"resultType": "complete",
                  "_meta": {"io.modelcontextprotocol/subscriptionId": "other", "io.modelcontextprotocol/serverInfo":
                    {"name": "alang", "version": "%s"}}}}
                """.formatted(Main.VERSION))), s.end());
        }
    }
}
