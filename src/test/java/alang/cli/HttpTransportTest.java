package alang.cli;

import static org.junit.jupiter.api.Assertions.*;

import alang.check.Checker;
import alang.diag.Source;
import alang.util.Json;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** `alang serve --http`: MCP's Streamable HTTP transport, with sessions, a token, logs and metrics. */
class HttpTransportTest {
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

        /// Adds up 0..n.
        tool fn spin(n: i64) -> i64 {
            var s: i64 = 0;
            for i in 0..n {
                s = wrapping_add(s, i);
            }
            return s;
        }
        """;

    private static final String TOKEN = "s3cret-token-0123456789";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private HttpTransport transport;
    private Watchdog watchdog;
    private String base;

    private static McpServer server(long maxSteps, Watchdog watchdog, int workers, int queued) {
        Frontend.Result r = Frontend.compile(new Source("bill.al", PROGRAM), Checker.DEFAULT_MAX_STACK);
        assertTrue(r.ok(), () -> r.diags().renderText());
        return new McpServer(r.diags().source(), r.program(), maxSteps, watchdog, workers, queued);
    }

    @BeforeEach
    void start() throws IOException {
        watchdog = new Watchdog(0);
        transport = new HttpTransport(server(0, watchdog, 2, 4),
            new HttpTransport.Config("127.0.0.1", 0, TOKEN, List.of("https://app.example.com")),
            new PrintStream(log, true, StandardCharsets.UTF_8));
        base = "http://127.0.0.1:" + transport.start();
    }

    @AfterEach
    void stop() {
        transport.stop();
        watchdog.close();
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String session, String body) throws Exception {
        List<String> h = new ArrayList<>(List.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json",
            "Accept", "application/json, text/event-stream"));
        if (session != null) h.addAll(List.of("Mcp-Session-Id", session));
        return send("POST", "/mcp", body, h.toArray(String[]::new));
    }

    private String initialize() throws Exception {
        HttpResponse<String> r = post(null, "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": "
            + "{\"protocolVersion\": \"2025-06-18\", \"capabilities\": {}, \"clientInfo\": {\"name\": \"t\", \"version\": \"1\"}}}");
        assertEquals(200, r.statusCode(), r.body());
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(r.body())).get("result");
        assertEquals("2025-06-18", result.get("protocolVersion"));
        assertEquals(false, ((Map<?, ?>) ((Map<?, ?>) result.get("capabilities")).get("tools")).get("listChanged"));
        String session = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(32, session.length());
        assertEquals(202, post(session, "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}").statusCode());
        return session;
    }

    private static String call(int id, String tool, String args) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"tools/call\", \"params\": {\"name\": \"" + tool
            + "\", \"arguments\": " + args + "}}";
    }

    @Test
    void servesASession() throws Exception {
        String session = initialize();
        HttpResponse<String> list = post(session, "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/list\"}");
        assertEquals(200, list.statusCode());
        assertTrue(list.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        List<?> tools = (List<?>) ((Map<?, ?>) ((Map<?, ?>) Json.parse(list.body())).get("result")).get("tools");
        assertEquals(2, tools.size());

        HttpResponse<String> r = post(session, call(3, "split_bill", "{\"total_cents\": 1000, \"people\": 3}"));
        assertEquals(200, r.statusCode(), r.body());
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) Json.parse(r.body())).get("result");
        assertEquals(false, result.get("isError"));
        assertEquals(List.of(334, 333, 333), ((List<?>) ((Map<?, ?>) result.get("structuredContent")).get("result"))
            .stream().map(n -> ((Number) n).intValue()).toList());

        assertEquals(204, send("DELETE", "/mcp", null, "Authorization", "Bearer " + TOKEN, "Mcp-Session-Id", session).statusCode());
        assertEquals(404, post(session, "{\"jsonrpc\": \"2.0\", \"id\": 4, \"method\": \"ping\"}").statusCode());
    }

    @Test
    void keepsTheIdsOfTwoSessionsApart() throws Exception {
        String a = initialize();
        String b = initialize();
        assertTrue(!a.equals(b));
        CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> {
            try {
                return post(a, call(7, "spin", "{\"n\": 1000000000000000}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // The same id in another session is a call of its own, not one "in use".
        HttpResponse<String> r = post(b, call(7, "split_bill", "{\"total_cents\": 10, \"people\": 2}"));
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"isError\": false"), r.body());
        // Cancel session a's call: its request is answered with a cancellation error.
        awaitInFlight(1);
        assertEquals(202, post(a, "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/cancelled\", \"params\": {\"requestId\": 7}}")
            .statusCode());
        HttpResponse<String> cancelled = slow.get(30, TimeUnit.SECONDS);
        assertEquals(200, cancelled.statusCode());
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) Json.parse(cancelled.body())).get("error");
        assertEquals(McpServer.REQUEST_CANCELLED, ((Number) error.get("code")).intValue());
    }

    /** Waits, up to about 30 s, until the metrics show {@code n} calls in flight. */
    private void awaitInFlight(int n) throws Exception {
        for (int i = 0; i < 30_000; i++) {
            if (transport.metrics().contains("alang_tool_calls_in_flight " + n + "\n")) return;
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        throw new AssertionError("never " + n + " calls in flight:\n" + transport.metrics());
    }

    @Test
    void refusesRequestsWithoutTheToken() throws Exception {
        HttpResponse<String> r = send("POST", "/mcp", "{}");
        assertEquals(401, r.statusCode());
        assertEquals("Bearer", r.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(401, send("POST", "/mcp", "{}", "Authorization", "Bearer wrong").statusCode());
        assertEquals(401, send("GET", "/metrics", null).statusCode());
        // The health check needs none.
        HttpResponse<String> health = send("GET", "/health", null);
        assertEquals(200, health.statusCode());
        assertEquals("ok", ((Map<?, ?>) Json.parse(health.body())).get("status"));
    }

    @Test
    void checksSessionsOriginsAndMethods() throws Exception {
        assertEquals(400, post(null, "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}").statusCode());
        assertEquals(404, post("feed", "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"ping\"}").statusCode());
        String auth = "Bearer " + TOKEN;
        assertEquals(403, send("POST", "/mcp", "{}", "Authorization", auth, "Origin", "https://evil.example").statusCode());
        assertEquals(400, send("POST", "/mcp", "{}", "Authorization", auth, "Origin", "https://app.example.com").statusCode());
        assertEquals(400, send("POST", "/mcp", "{}", "Authorization", auth, "Origin", "http://localhost:3000").statusCode());
        HttpResponse<String> get = send("GET", "/mcp", null, "Authorization", auth);
        assertEquals(405, get.statusCode());
        assertEquals(404, send("GET", "/other", null, "Authorization", auth).statusCode());
        assertEquals(400, send("POST", "/mcp", "{}", "Authorization", auth, "MCP-Protocol-Version", "1999-01-01").statusCode());
        String session = initialize();
        HttpResponse<String> junk = post(session, "not json");
        assertEquals(200, junk.statusCode());
        assertTrue(junk.body().contains("-32700"), junk.body());
        HttpResponse<String> big = post(session, "[" + "1,".repeat(HttpTransport.MAX_BODY / 2) + "1]");
        assertEquals(413, big.statusCode());
    }

    @Test
    void logsAndCounts() throws Exception {
        String session = initialize();
        post(session, call(2, "split_bill", "{\"total_cents\": 1000, \"people\": 3}"));
        post(session, call(3, "split_bill", "{\"total_cents\": 1000, \"people\": 0}"));
        post(session, call(4, "split_bill", "{\"total_cents\": \"x\", \"people\": 0}"));
        String metrics = send("GET", "/metrics", null, "Authorization", "Bearer " + TOKEN).body();
        assertTrue(metrics.contains("alang_tool_calls_total{tool=\"split_bill\",outcome=\"ok\"} 1\n"), metrics);
        assertTrue(metrics.contains("alang_tool_calls_total{tool=\"split_bill\",outcome=\"R0006\"} 1\n"), metrics);
        assertTrue(metrics.contains("alang_tool_calls_total{tool=\"split_bill\",outcome=\"bad_arguments\"} 1\n"), metrics);
        assertTrue(metrics.contains("alang_tool_call_steps_total{tool=\"split_bill\"} 16\n"), metrics);
        assertTrue(metrics.contains("alang_sessions 1\n"), metrics);
        assertTrue(metrics.contains("# TYPE alang_http_requests_total counter\n"), metrics);

        List<Map<?, ?>> events = new ArrayList<>();
        for (String line : log.toString(StandardCharsets.UTF_8).split("\n")) events.add((Map<?, ?>) Json.parse(line));
        assertEquals("listening", events.get(0).get("event"));
        Map<?, ?> callEvent = events.stream().filter(e -> "call".equals(e.get("event"))).findFirst().orElseThrow();
        assertEquals("split_bill", callEvent.get("tool"));
        assertEquals("ok", callEvent.get("outcome"));
        assertNotNull(callEvent.get("time"));
        Map<?, ?> request = events.stream().filter(e -> "tools/call".equals(e.get("rpc"))).findFirst().orElseThrow();
        assertEquals(session, request.get("session"));
        assertEquals(200, ((Number) request.get("status")).intValue());
    }

    @Test
    void needsATokenToListenBeyondThisMachine() {
        McpServer s = server(0, watchdog, 1, 1);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
            () -> new HttpTransport(s, new HttpTransport.Config("0.0.0.0", 0, null, List.of()), sink));
        assertTrue(HttpTransport.isLoopback("localhost"));
        assertTrue(HttpTransport.isLoopback("::1"));
    }

    @Test
    void refusesCallsWhenTheQueueIsFull() throws Exception {
        McpServer s = server(0, watchdog, 1, 1);
        s.start();
        try {
            McpServer.Session session = s.newSession(null);
            List<CompletableFuture<Map<String, Object>>> answers = new ArrayList<>();
            List<Map<String, Object>> immediate = new ArrayList<>();
            for (int id = 1; id <= 3; id++) {
                CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
                answers.add(f);
                immediate.add(s.handle(session, call(id, "spin", "{\"n\": 1000000000000000}").getBytes(StandardCharsets.UTF_8),
                    f::complete));
            }
            assertTrue(immediate.get(0) == McpServer.PENDING);
            assertTrue(immediate.get(1) == McpServer.PENDING);
            Map<?, ?> busy = (Map<?, ?>) immediate.get(2).get("error");
            assertEquals(McpServer.SERVER_BUSY, ((Number) busy.get("code")).intValue());
            s.endSession(session, "test over");
            for (int i = 0; i < 2; i++) assertEquals(null, answers.get(i).get(30, TimeUnit.SECONDS));
        } finally {
            s.stop();
        }
    }

    @Test
    void cliRefusesBadHttpOptions(@TempDir Path dir) throws IOException {
        Path prog = dir.resolve("bill.al");
        Files.writeString(prog, PROGRAM);
        Path shortToken = dir.resolve("short");
        Files.writeString(shortToken, "abc\n");
        assertEquals(2, cli("serve", prog.toString(), "--http", "x:y"));
        assertEquals(2, cli("serve", prog.toString(), "--http", "70000"));
        assertEquals(2, cli("serve", prog.toString(), "--token-file", shortToken.toString()));
        assertEquals(2, cli("serve", prog.toString(), "--http", "0", "--token-file", shortToken.toString()));
        assertEquals(2, cli("serve", prog.toString(), "--http", "0.0.0.0:0"));
        assertEquals(2, cli("serve", prog.toString(), "--workers", "0"));
    }

    // ---------------------------------------------------------------- the modern revision (2026-07-28)

    /** A modern request; {@code params} without braces, the _meta added. */
    private static String modern(Object id, String method, String params, String capabilities) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": {"
            + (params.isEmpty() ? "" : params + ", ") + "\"_meta\": {\"io.modelcontextprotocol/protocolVersion\": \"2026-07-28\", "
            + "\"io.modelcontextprotocol/clientCapabilities\": " + capabilities + "}}}";
    }

    private static String modern(Object id, String method, String params) {
        return modern(id, method, params, "{}");
    }

    /** POSTs {@code body} with the token and {@code headers}. */
    private HttpResponse<String> postWith(String body, String... headers) throws Exception {
        List<String> h = new ArrayList<>(List.of("Authorization", "Bearer " + TOKEN, "Content-Type", "application/json",
            "Accept", "application/json, text/event-stream"));
        h.addAll(List.of(headers));
        return send("POST", "/mcp", body, h.toArray(String[]::new));
    }

    /** POSTs modern request {@code body} with the headers that mirror it: the revision, the method and, if given, the name. */
    private HttpResponse<String> postModern(String body, String method, String name) throws Exception {
        return name == null ? postWith(body, "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", method)
            : postWith(body, "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", method, "Mcp-Name", name);
    }

    /** The JSON-RPC message of a response: its body, or the last event of an event stream. */
    private static Map<?, ?> answerOf(HttpResponse<String> r) {
        String body = r.body();
        if (r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            List<String> data = body.lines().filter(l -> l.startsWith("data: ")).toList();
            assertFalse(data.isEmpty(), body);
            body = data.get(data.size() - 1).substring(6);
        }
        return (Map<?, ?>) Json.parse(body);
    }

    private static int errorCode(HttpResponse<String> r) {
        Map<?, ?> error = (Map<?, ?>) answerOf(r).get("error");
        assertNotNull(error, r.body());
        return ((Number) error.get("code")).intValue();
    }

    /** A modern request needs no session: none is made, and one it names is not looked at. */
    @Test
    void servesModernRequestsWithoutASession() throws Exception {
        HttpResponse<String> discover = postModern(modern(1, "server/discover", ""), "server/discover", null);
        assertEquals(200, discover.statusCode(), discover.body());
        Map<?, ?> result = (Map<?, ?>) answerOf(discover).get("result");
        assertEquals(McpServer.PROTOCOL_VERSIONS, result.get("supportedVersions"));
        assertEquals("complete", result.get("resultType"));
        assertTrue(discover.headers().firstValue("Mcp-Session-Id").isEmpty());

        String call = modern(2, "tools/call", "\"name\": \"split_bill\", \"arguments\": {\"total_cents\": 1000, \"people\": 3}");
        HttpResponse<String> r = postModern(call, "tools/call", "split_bill");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        Map<?, ?> called = (Map<?, ?>) answerOf(r).get("result");
        assertEquals(List.of(334, 333, 333), ((List<?>) ((Map<?, ?>) called.get("structuredContent")).get("result"))
            .stream().map(n -> ((Number) n).intValue()).toList());
        assertEquals("alang", ((Map<?, ?>) ((Map<?, ?>) called.get("_meta")).get(McpServer.SERVER_INFO)).get("name"));
        // A name written in Base64, and a session id from an older revision, which is ignored.
        String encoded = "=?base64?" + Base64.getEncoder().encodeToString("split_bill".getBytes(StandardCharsets.UTF_8)) + "?=";
        HttpResponse<String> again = postWith(call, "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call",
            "Mcp-Name", encoded, "Mcp-Session-Id", "feed");
        assertEquals(200, again.statusCode(), again.body());
        assertTrue(again.headers().firstValue("Mcp-Session-Id").isEmpty());
        assertTrue(transport.metrics().contains("alang_sessions 0\n"), transport.metrics());
    }

    /**
     * The headers must mirror the body (400, -32020); the revision must be one the server speaks (400, -32022,
     * with the ones it does) and the _meta whole (400, -32602); an unknown method is 404.
     */
    @Test
    void checksModernRequests() throws Exception {
        String call = modern(1, "tools/call", "\"name\": \"split_bill\", \"arguments\": {\"total_cents\": 10, \"people\": 2}");
        HttpResponse<String> r = postWith(call, "Mcp-Method", "tools/call", "Mcp-Name", "split_bill");
        assertEquals(400, r.statusCode());
        assertEquals(McpServer.HEADER_MISMATCH, errorCode(r));
        assertTrue(r.body().contains("MCP-Protocol-Version header is missing"), r.body());
        r = postModern(call, "tools/list", "split_bill");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("Mcp-Method header value 'tools/list' does not match body value 'tools/call'"), r.body());
        r = postModern(call, "tools/call", null);
        assertEquals(McpServer.HEADER_MISMATCH, errorCode(r));
        r = postModern(call, "tools/call", "spin");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("Mcp-Name header value 'spin' does not match body value 'split_bill'"), r.body());
        r = postModern(call.replace("2026-07-28", "2025-11-25"), "tools/call", "split_bill");
        assertEquals(McpServer.HEADER_MISMATCH, errorCode(r));

        r = postWith(call, "MCP-Protocol-Version", "2099-01-01", "Mcp-Method", "tools/call", "Mcp-Name", "split_bill");
        assertEquals(400, r.statusCode());
        Map<?, ?> unsupported = (Map<?, ?>) answerOf(r).get("error");
        assertEquals(McpServer.UNSUPPORTED_PROTOCOL_VERSION, ((Number) unsupported.get("code")).intValue());
        assertEquals(1, ((Number) answerOf(r).get("id")).intValue());
        assertEquals(Map.of("supported", McpServer.PROTOCOL_VERSIONS, "requested", "2099-01-01"), unsupported.get("data"));
        r = postWith(call.replace("2026-07-28", "2099-01-01"), "MCP-Protocol-Version", "2099-01-01", "Mcp-Method", "tools/call",
            "Mcp-Name", "split_bill");
        assertEquals(McpServer.UNSUPPORTED_PROTOCOL_VERSION, errorCode(r));

        r = postModern(modern(2, "tools/list", "", "null"), "tools/list", null);
        assertEquals(400, r.statusCode());
        assertEquals(McpServer.INVALID_PARAMS, errorCode(r));
        r = postModern("{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"tools/list\"}", "tools/list", null);
        assertEquals(400, r.statusCode());
        assertEquals(McpServer.INVALID_PARAMS, errorCode(r));
        r = postModern(modern(4, "prompts/list", ""), "prompts/list", null);
        assertEquals(404, r.statusCode());
        assertEquals(McpServer.METHOD_NOT_FOUND, errorCode(r));
        r = postModern(modern(5, "ping", ""), "ping", null);
        assertEquals(404, r.statusCode());
        // A failing call is a result, as before.
        r = postModern(modern(6, "tools/call", "\"name\": \"nope\""), "tools/call", "nope");
        assertEquals(200, r.statusCode());
        assertEquals(McpServer.INVALID_PARAMS, errorCode(r));
    }

    /**
     * A modern call still running after a second is answered as an event stream, kept alive with comments; a
     * client that closes it cancels the call.
     */
    @Test
    void closingTheStreamOfAModernCallCancelsIt() throws Exception {
        String body = modern(7, "tools/call", "\"name\": \"spin\", \"arguments\": {\"n\": 1000000000000000}");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", URI.create(base).getPort())) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN + "\r\n"
                + "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "MCP-Protocol-Version: 2026-07-28\r\nMcp-Method: tools/call\r\nMcp-Name: spin\r\n"
                + "Content-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            List<String> head = new ArrayList<>();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) head.add(line);
            assertEquals("HTTP/1.1 200 OK", head.get(0));
            assertTrue(head.stream().anyMatch(l -> l.equalsIgnoreCase("Content-Type: text/event-stream")), head.toString());
            // The first chunk: its size, then a comment.
            in.readLine();
            assertEquals(":", in.readLine());
        }
        awaitInFlight(0);
        assertTrue(transport.metrics().contains("alang_tool_calls_total{tool=\"spin\",outcome=\"cancelled\"} 1\n"),
            transport.metrics());
    }

    /** A stream ends with the call's answer, here the error result of one stopped at its time limit. */
    @Test
    void aStreamedCallEndsWithItsAnswer() throws Exception {
        Watchdog limit = new Watchdog(1500);
        HttpTransport t = new HttpTransport(server(0, limit, 1, 1), new HttpTransport.Config("127.0.0.1", 0, TOKEN, List.of()),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String saved = base;
        try {
            base = "http://127.0.0.1:" + t.start();
            HttpResponse<String> r = postModern(modern(8, "tools/call", "\"name\": \"spin\", \"arguments\": {\"n\": 1000000000000000}"),
                "tools/call", "spin");
            assertEquals(200, r.statusCode());
            assertEquals("text/event-stream", r.headers().firstValue("Content-Type").orElseThrow());
            Map<?, ?> answer = answerOf(r);
            assertEquals(8, ((Number) answer.get("id")).intValue());
            assertEquals(true, ((Map<?, ?>) answer.get("result")).get("isError"));
        } finally {
            base = saved;
            t.stop();
            limit.close();
        }
    }

    /** subscriptions/listen is a stream that starts with its acknowledgment and that the server answers as it stops. */
    @Test
    void listensUntilTheServerStops() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/mcp")).timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(modern("\"s\"", "subscriptions/listen", "\"notifications\": {\"toolsListChanged\": true}")))
            .header("Authorization", "Bearer " + TOKEN).header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "subscriptions/listen").build();
        HttpResponse<java.util.stream.Stream<String>> r = client.send(request, HttpResponse.BodyHandlers.ofLines());
        assertEquals("text/event-stream", r.headers().firstValue("Content-Type").orElseThrow());
        Iterator<String> lines = r.body().filter(l -> l.startsWith("data: ")).map(l -> l.substring(6)).iterator();
        // Without --watch, the tools never change: nothing to agree to.
        assertEquals(Json.parse("""
            {"jsonrpc": "2.0", "method": "notifications/subscriptions/acknowledged",
              "params": {"_meta": {"io.modelcontextprotocol/subscriptionId": "s"}, "notifications": {}}}
            """), Json.parse(lines.next()));
        transport.stop();
        Map<?, ?> closing = (Map<?, ?>) Json.parse(lines.next());
        assertEquals("s", closing.get("id"));
        assertEquals("s", ((Map<?, ?>) ((Map<?, ?>) closing.get("result")).get("_meta")).get(McpServer.SUBSCRIPTION_ID));
        assertFalse(lines.hasNext());
    }

    /** Modern requests share their tasks, whose ids cannot be guessed; a legacy session does not see them. */
    @Test
    void modernRequestsShareTasks() throws Exception {
        McpServer s = server(0, watchdog, 1, 1);
        s.tasks(50, System::currentTimeMillis);
        HttpTransport t = new HttpTransport(s, new HttpTransport.Config("127.0.0.1", 0, TOKEN, List.of()),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        String saved = base;
        try {
            base = "http://127.0.0.1:" + t.start();
            HttpResponse<String> r = postModern(modern(1, "tools/call", "\"name\": \"spin\", \"arguments\": {\"n\": 1000000000000000}",
                "{\"extensions\": {\"io.modelcontextprotocol/tasks\": {}}}"), "tools/call", "spin");
            Map<?, ?> task = (Map<?, ?>) answerOf(r).get("result");
            assertEquals("task", task.get("resultType"), r.body());
            String id = (String) task.get("taskId");
            assertTrue(id.matches("[0-9a-f]{32}"), id);
            r = postModern(modern(1, "tasks/get", "\"taskId\": \"" + id + "\""), "tasks/get", null);
            assertEquals("working", ((Map<?, ?>) answerOf(r).get("result")).get("status"), r.body());
            String session = initialize();
            r = post(session, "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tasks/get\", \"params\": {\"taskId\": \"" + id + "\"}}");
            assertEquals(McpServer.INVALID_PARAMS, errorCode(r));
            r = postModern(modern(3, "tasks/cancel", "\"taskId\": \"" + id + "\""), "tasks/cancel", null);
            assertEquals("cancelled", ((Map<?, ?>) answerOf(r).get("result")).get("status"), r.body());
        } finally {
            base = saved;
            t.stop();
        }
    }

    private static int cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
            new java.io.ByteArrayInputStream(new byte[0])).run(args);
    }
}
