package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.check.Checker;
import alang.diag.Source;
import alang.util.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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
        """;

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
                  "capabilities": {"tools": {"listChanged": false}},
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
            assertTrue(reply.contains("\"protocolVersion\": \"" + McpServer.PROTOCOL_VERSIONS.get(0) + "\""), reply);
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
}
