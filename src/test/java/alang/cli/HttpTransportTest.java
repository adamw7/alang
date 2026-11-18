package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.check.Checker;
import alang.diag.Source;
import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private static int cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
            new java.io.ByteArrayInputStream(new byte[0])).run(args);
    }
}
