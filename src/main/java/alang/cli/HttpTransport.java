package alang.cli;

import alang.util.Json;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import javax.net.ssl.*;

/**
 * `alang serve --http`: the tools of {@link McpServer} over MCP's Streamable HTTP transport, for a host that
 * reaches the server over the network rather than starting it as a child process.
 *
 * <ul>
 *   <li>{@code POST /mcp} takes one JSON-RPC message. A request is answered with one JSON response
 *       ({@code application/json}); a notification or a response gets 202 and no body. {@code initialize}
 *       starts a session, whose id comes back in the {@code Mcp-Session-Id} header, and every later message
 *       carries it, so that the request ids and cancellations of two clients never meet.</li>
 *   <li>{@code DELETE /mcp} ends the session, stopping its calls. {@code GET /mcp} is 405: the server sends no
 *       messages of its own, so the tools' listChanged capability is false; a client lists them again.</li>
 *   <li>{@code GET /health} says the server is up, without authentication, for a load balancer.</li>
 *   <li>{@code GET /metrics} gives counters in the Prometheus text format.</li>
 * </ul>
 *
 * <p>With a certificate and its key ({@code --tls-cert}, {@code --tls-key}) the server speaks HTTPS, and only
 * TLS 1.3: a client that offers nothing newer is refused in the handshake.
 *
 * <p>With a token ({@code --token-file}), every request but {@code /health} needs
 * {@code Authorization: Bearer TOKEN}. Without one the server only listens on a loopback address. A request
 * with an {@code Origin} header is refused unless that origin is allowed, or is on this machine while the
 * server listens only there, as MCP asks against DNS rebinding.
 *
 * <p>Each request and each tool call is logged as one line of JSON on the log stream. This class, with
 * {@link McpServer} and {@link Watchdog}, is the host-facing edge of the toolchain: it alone reads the clock
 * (for the log and the metrics), draws random numbers (for session ids) and uses the JDK's HTTP server (see
 * ArchitectureTest). A tool call runs exactly as `alang call` runs it.
 */
final class HttpTransport implements McpServer.Observer {
    /** The path of the MCP endpoint. */
    static final String ENDPOINT = "/mcp";

    /** The largest message body taken: 4 MiB, well above the largest arguments a tool can take in practice. */
    static final int MAX_BODY = 4 << 20;

    /** The most sessions at once; when full, the one idle longest without a running call makes way. */
    static final int MAX_SESSIONS = 1024;

    /** A session unused this long (ns) ends on its own. */
    static final long SESSION_IDLE_NANOS = 3600L * 1_000_000_000L;

    /** How long (s) stopping waits for requests in progress. */
    private static final int DRAIN_SECONDS = 5;

    /** The one TLS version spoken with --tls-cert and --tls-key. */
    static final String TLS_PROTOCOL = "TLSv1.3";

    /**
     * How the server is set up: where it listens, the token (null: none), the origins allowed besides its own, and
     * for HTTPS the PEM text of the certificate chain and of its private key (both null: plain HTTP).
     */
    record Config(String host, int port, String token, List<String> allowedOrigins, String tlsCert, String tlsKey) {
        /** Plain HTTP. */
        Config(String host, int port, String token, List<String> allowedOrigins) {
            this(host, port, token, allowedOrigins, null, null);
        }
    }

    private final McpServer mcp;
    private final Config config;
    private final PrintStream log;
    private final boolean loopbackOnly;
    private final byte[] token;
    /** What serves HTTPS, or null for plain HTTP. */
    private final SSLContext tls;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService handlers;

    // Metrics.
    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> calls = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> steps = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> callNanos = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> callCount = new ConcurrentHashMap<>();
    private final AtomicLong inFlight = new AtomicLong();

    /** A session and when it was last used (System.nanoTime). */
    private static final class Entry {
        final McpServer.Session session;
        volatile long lastUsed;

        Entry(McpServer.Session session, long now) {
            this.session = session;
            this.lastUsed = now;
        }
    }

    HttpTransport(McpServer mcp, Config config, PrintStream log) {
        this.mcp = mcp;
        this.config = config;
        this.log = log;
        this.loopbackOnly = isLoopback(config.host());
        this.token = config.token() == null ? null : config.token().getBytes(StandardCharsets.UTF_8);
        if (token == null && !loopbackOnly) {
            throw new IllegalArgumentException("listening on " + config.host() + " needs a token (--token-file): "
                + "without one, anyone who can reach the port could call the tools");
        }
        if ((config.tlsCert() == null) != (config.tlsKey() == null)) {
            throw new IllegalArgumentException("--tls-cert and --tls-key go together: HTTPS needs the certificate and its key");
        }
        this.tls = config.tlsCert() == null ? null : tls(config.tlsCert(), config.tlsKey());
        mcp.observe(this);
    }

    /** Whether {@code host} names only this machine. */
    static boolean isLoopback(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (!a.isLoopbackAddress()) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Starts listening; returns the port, which is the one asked for unless that was 0. */
    int start() throws IOException {
        mcp.start();
        InetSocketAddress address = new InetSocketAddress(config.host(), config.port());
        if (tls == null) {
            server = HttpServer.create(address, 64);
        } else {
            HttpsServer https = HttpsServer.create(address, 64);
            https.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters p = tls.getDefaultSSLParameters();
                    p.setProtocols(new String[] {TLS_PROTOCOL});
                    params.setSSLParameters(p);
                }
            });
            server = https;
        }
        // A request waiting for its call holds a thread; virtual ones make that cheap, and the calls themselves
        // are bounded by McpServer's workers and queue.
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/", this::exchange);
        server.start();
        int port = server.getAddress().getPort();
        Map<String, Object> listening = Json.obj("event", "listening", "host", config.host(), "port", port, "endpoint", ENDPOINT,
            "auth", token != null, "tls", tls == null ? false : TLS_PROTOCOL, "tools", mcp.toolCount());
        if (tls == null && !loopbackOnly) {
            listening.put("warning", "the token crosses the network in plain text: give --tls-cert and --tls-key, "
                + "or serve behind a proxy that terminates TLS");
        }
        logEvent(listening);
        return port;
    }

    /** Stops the server when the JVM is asked to end (SIGINT, SIGTERM), letting calls in progress finish. */
    void stopOnShutdown() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "alang-shutdown"));
    }

    /** Blocks until {@link #stop}; if this thread is interrupted, stops the server first. */
    void await() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            stop();
            Thread.currentThread().interrupt();
        }
    }

    /** Stops listening, ends the sessions and waits for the calls still running. */
    synchronized void stop() {
        if (stopped.getCount() == 0) return;
        if (server != null) server.stop(DRAIN_SECONDS);
        for (Entry e : sessions.values()) mcp.endSession(e.session, "the server is shutting down");
        sessions.clear();
        mcp.stop();
        if (handlers != null) handlers.shutdown();
        logEvent(Json.obj("event", "stopped"));
        stopped.countDown();
    }

    // =================================================================== requests

    private void exchange(HttpExchange ex) throws IOException {
        long start = System.nanoTime();
        Map<String, Object> entry = Json.obj("event", "request", "method", ex.getRequestMethod(),
            "path", ex.getRequestURI().getPath());
        int status;
        try {
            status = route(ex, entry);
        } catch (RuntimeException e) {
            entry.put("error", e.toString());
            status = respond(ex, 500, Json.obj("error", "internal error"));
        } finally {
            ex.close();
        }
        entry.put("status", status);
        entry.put("ms", (System.nanoTime() - start) / 1_000_000);
        requests.computeIfAbsent(String.valueOf(status), k -> new LongAdder()).increment();
        logEvent(entry);
    }

    private int route(HttpExchange ex, Map<String, Object> entry) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (path.equals("/health")) {
            if (!method.equals("GET")) return notAllowed(ex, "GET");
            return respond(ex, 200, Json.obj("status", "ok", "version", Main.VERSION, "tools", mcp.toolCount()));
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !originAllowed(origin)) {
            entry.put("origin", origin);
            return respond(ex, 403, Json.obj("error", "origin not allowed: " + origin));
        }
        if (!authorized(ex.getRequestHeaders())) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            return respond(ex, 401, Json.obj("error", "a valid Authorization: Bearer token is required"));
        }
        if (path.equals("/metrics")) {
            if (!method.equals("GET")) return notAllowed(ex, "GET");
            byte[] body = metrics().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            return 200;
        }
        if (!path.equals(ENDPOINT)) return respond(ex, 404, Json.obj("error", "not found; the MCP endpoint is " + ENDPOINT));
        String version = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (version != null && !McpServer.PROTOCOL_VERSIONS.contains(version)) {
            return respond(ex, 400, Json.obj("error", "unsupported MCP-Protocol-Version: " + version
                + "; supported: " + McpServer.PROTOCOL_VERSIONS));
        }
        return switch (method) {
            case "POST" -> post(ex, entry);
            case "DELETE" -> delete(ex, entry);
            default -> notAllowed(ex, "POST, DELETE");
        };
    }

    private int post(HttpExchange ex, Map<String, Object> entry) throws IOException {
        byte[] body = readBody(ex.getRequestBody());
        if (body == null) return respond(ex, 413, Json.obj("error", "the message is larger than " + MAX_BODY + " bytes"));
        Object message = parseQuietly(body);
        String rpc = message instanceof Map<?, ?> m && m.get("method") instanceof String s ? s : null;
        if (rpc != null) entry.put("rpc", rpc);
        if (rpc != null && rpc.equals("tools/call") && ((Map<?, ?>) message).get("params") instanceof Map<?, ?> p
                && p.get("name") instanceof String tool) {
            entry.put("tool", tool);
        }
        String id = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        Entry session;
        boolean created = false;
        if ("initialize".equals(rpc)) {
            id = newSession();
            if (id == null) {
                return respond(ex, 503, Json.obj("error", "too many sessions (" + MAX_SESSIONS + "); end one with DELETE"));
            }
            session = sessions.get(id);
            created = true;
        } else if (id == null) {
            return respond(ex, 400, Json.obj("error", "the Mcp-Session-Id header is missing: send initialize first"));
        } else {
            session = sessions.get(id);
            if (session == null) return respond(ex, 404, Json.obj("error", "no session " + id + ": it ended; initialize again"));
            session.lastUsed = System.nanoTime();
        }
        String sessionId = id;
        entry.put("session", sessionId);
        CompletableFuture<Map<String, Object>> later = new CompletableFuture<>();
        Map<String, Object> answer = mcp.handle(session.session, body, later::complete);
        if (answer == McpServer.PENDING) {
            try {
                answer = later.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                answer = null;
            } catch (ExecutionException e) {
                answer = null;
            }
            if (answer == null) {
                Object rid = ((Map<?, ?>) message).get("id");
                answer = McpServer.error(rid, McpServer.REQUEST_CANCELLED, "the call was cancelled");
            }
        }
        if (created) {
            if (answer != null && answer.containsKey("error")) {
                sessions.remove(sessionId);
                mcp.endSession(session.session, "initialize failed");
            } else {
                ex.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            }
        }
        if (answer == null) {
            ex.sendResponseHeaders(202, -1);
            return 202;
        }
        return respond(ex, 200, answer);
    }

    private int delete(HttpExchange ex, Map<String, Object> entry) throws IOException {
        String id = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (id == null) return respond(ex, 400, Json.obj("error", "the Mcp-Session-Id header is missing"));
        entry.put("session", id);
        Entry e = sessions.remove(id);
        if (e == null) return respond(ex, 404, Json.obj("error", "no session " + id));
        mcp.endSession(e.session, "the client ended the session");
        ex.sendResponseHeaders(204, -1);
        return 204;
    }

    // =================================================================== sessions

    /** The id of a new session, after ending idle ones; null when all {@link #MAX_SESSIONS} are busy. */
    private synchronized String newSession() {
        long now = System.nanoTime();
        sessions.entrySet().removeIf(e -> {
            boolean idle = now - e.getValue().lastUsed > SESSION_IDLE_NANOS && !e.getValue().session.busy();
            if (idle) mcp.endSession(e.getValue().session, "the session expired");
            return idle;
        });
        if (sessions.size() >= MAX_SESSIONS) {
            String oldest = null;
            long oldestUse = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : sessions.entrySet()) {
                if (!e.getValue().session.busy() && e.getValue().lastUsed < oldestUse) {
                    oldest = e.getKey();
                    oldestUse = e.getValue().lastUsed;
                }
            }
            if (oldest == null) return null;
            mcp.endSession(sessions.remove(oldest).session, "too many sessions");
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        sessions.put(id, new Entry(mcp.newSession(null), now));
        return id;
    }

    // =================================================================== TLS

    private static final Pattern PEM = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");

    /**
     * The context that serves HTTPS with TLS 1.3 from the PEM text of a certificate chain (the server's own
     * certificate first, as in a fullchain.pem) and of its private key, unencrypted PKCS#8 ("BEGIN PRIVATE KEY"):
     * RSA, EC or EdDSA. Throws IllegalArgumentException, saying what is wrong, when they cannot serve.
     */
    static SSLContext tls(String certPem, String keyPem) {
        List<X509Certificate> chain = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String[] block : pem(certPem)) {
                if (!block[0].equals("CERTIFICATE")) continue;
                chain.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(block[1].getBytes(StandardCharsets.US_ASCII))));
            }
        } catch (CertificateException | IllegalArgumentException e) {
            throw new IllegalArgumentException("--tls-cert: not a valid PEM certificate: " + e.getMessage());
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("--tls-cert: no \"-----BEGIN CERTIFICATE-----\" block: give the server's "
                + "certificate in PEM, followed by any intermediate certificates");
        }
        PublicKey pub = chain.get(0).getPublicKey();
        PrivateKey key = privateKey(keyPem, pub.getAlgorithm());
        if (!pairs(key, pub)) {
            throw new IllegalArgumentException("--tls-key is not the private key of the first certificate in --tls-cert");
        }
        try {
            char[] password = "alang".toCharArray();  // the store lives only in memory
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("server", key, password, chain.toArray(new X509Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            SSLContext context = SSLContext.getInstance(TLS_PROTOCOL);
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("cannot serve TLS with --tls-cert and --tls-key: " + e.getMessage());
        }
    }

    /** Each PEM block of {@code text}: its label and its whole text, BEGIN and END lines included. */
    private static List<String[]> pem(String text) {
        List<String[]> blocks = new ArrayList<>();
        Matcher m = PEM.matcher(text);
        while (m.find()) blocks.add(new String[] {m.group(1), m.group()});
        return blocks;
    }

    /** The private key in {@code text}, of the algorithm of the certificate's public key. */
    private static PrivateKey privateKey(String text, String algorithm) {
        String convert = "; write it as unencrypted PKCS#8 with: openssl pkey -in KEY -out key.pem";
        for (String[] block : pem(text)) {
            switch (block[0]) {
                case "PRIVATE KEY" -> {
                    String base64 = block[1].replaceAll("-----[A-Z0-9 ]+-----", "");
                    try {
                        byte[] der = Base64.getMimeDecoder().decode(base64);
                        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
                    } catch (GeneralSecurityException | IllegalArgumentException e) {
                        throw new IllegalArgumentException("--tls-key: not a valid " + algorithm + " private key, as the certificate's "
                            + "public key is: " + e.getMessage());
                    }
                }
                case "ENCRYPTED PRIVATE KEY" -> throw new IllegalArgumentException("--tls-key: the key is encrypted" + convert);
                case "RSA PRIVATE KEY", "EC PRIVATE KEY" ->
                    throw new IllegalArgumentException("--tls-key: the key is in the older " + block[0] + " format" + convert);
                default -> { }
            }
        }
        throw new IllegalArgumentException("--tls-key: no \"-----BEGIN PRIVATE KEY-----\" block" + convert);
    }

    /** Whether {@code key} signs what {@code pub} verifies; true for a kind of key this cannot test, as the handshake will. */
    private static boolean pairs(PrivateKey key, PublicKey pub) {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "EdDSA", "Ed25519", "Ed448" -> "EdDSA";
            default -> null;
        };
        if (algorithm == null) return true;
        try {
            byte[] probe = "alang tls".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(pub);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    // =================================================================== checks

    /** Whether the request carries the token; always, when there is none. Compared in constant time. */
    private boolean authorized(Headers h) {
        if (token == null) return true;
        String auth = h.getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(token, auth.substring(7).strip().getBytes(StandardCharsets.UTF_8));
    }

    private boolean originAllowed(String origin) {
        if (config.allowedOrigins().contains(origin)) return true;
        if (!loopbackOnly) return false;
        try {
            String host = new URI(origin).getHost();
            return host != null && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // =================================================================== tool calls (McpServer.Observer)

    @Override
    public Object started(String tool) {
        inFlight.incrementAndGet();
        return System.nanoTime();
    }

    @Override
    public void ended(Object token, String tool, Map<String, Object> report) {
        inFlight.decrementAndGet();
        long nanos = System.nanoTime() - (Long) token;
        String outcome;
        Object stepCount = null;
        if (report == null) {
            outcome = "cancelled";
        } else if (report.get("ok") == Boolean.TRUE) {
            outcome = "ok";
            stepCount = report.get("steps");
        } else {
            outcome = report.get("error") instanceof Map<?, ?> e && e.get("code") instanceof String c ? c : "error";
            stepCount = report.get("steps");
        }
        calls.computeIfAbsent(label("tool", tool) + "," + label("outcome", outcome), k -> new LongAdder()).increment();
        callNanos.computeIfAbsent(label("tool", tool), k -> new LongAdder()).add(nanos);
        callCount.computeIfAbsent(label("tool", tool), k -> new LongAdder()).increment();
        if (stepCount instanceof Number n) steps.computeIfAbsent(label("tool", tool), k -> new LongAdder()).add(n.longValue());
        Map<String, Object> e = Json.obj("event", "call", "tool", tool, "outcome", outcome, "ms", nanos / 1_000_000);
        if (stepCount != null) e.put("steps", stepCount);
        logEvent(e);
    }

    // =================================================================== metrics

    /** The counters in the Prometheus text exposition format, each family sorted by its labels. */
    String metrics() {
        StringBuilder sb = new StringBuilder();
        family(sb, "alang_info", "gauge", "The version served.", Map.of(label("version", Main.VERSION), 1L));
        family(sb, "alang_http_requests_total", "counter", "HTTP requests by status.", prefixed("status", requests));
        family(sb, "alang_tool_calls_total", "counter", "Tool calls by tool and outcome (ok, or the error code).", sums(calls));
        family(sb, "alang_tool_call_steps_total", "counter", "Steps run by tool calls.", sums(steps));
        Map<String, Object> seconds = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : callNanos.entrySet()) {
            seconds.put(e.getKey(), java.math.BigDecimal.valueOf(e.getValue().sum(), 9).stripTrailingZeros().toPlainString());
        }
        family(sb, "alang_tool_call_seconds_sum", "counter", "Time spent in tool calls, queueing included.", seconds);
        family(sb, "alang_tool_call_seconds_count", "counter", "Tool calls timed.", sums(callCount));
        family(sb, "alang_tool_calls_in_flight", "gauge", "Tool calls waiting or running.", Map.of("", inFlight.get()));
        family(sb, "alang_sessions", "gauge", "Open MCP sessions.", Map.of("", (long) sessions.size()));
        return sb.toString();
    }

    private static Map<String, Object> sums(Map<String, LongAdder> m) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : m.entrySet()) out.put(e.getKey(), e.getValue().sum());
        return out;
    }

    private static Map<String, Object> prefixed(String name, Map<String, LongAdder> m) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : m.entrySet()) out.put(label(name, e.getKey()), e.getValue().sum());
        return out;
    }

    private static void family(StringBuilder sb, String name, String type, String help, Map<String, ?> samples) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        for (Map.Entry<String, ?> e : new TreeMap<>(samples).entrySet()) {
            sb.append(name);
            if (!e.getKey().isEmpty()) sb.append('{').append(e.getKey()).append('}');
            sb.append(' ').append(e.getValue()).append('\n');
        }
    }

    /** {@code name="value"}, the value escaped as the Prometheus format asks. */
    private static String label(String name, String value) {
        return name + "=\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    // =================================================================== helpers

    /** One line of JSON on the log, with the time first. */
    private void logEvent(Map<String, Object> event) {
        Map<String, Object> line = Json.obj("time", Instant.now().toString());
        line.putAll(event);
        String text = Json.write(line) + "\n";
        synchronized (log) {
            log.print(text);
            log.flush();
        }
    }

    private static int respond(HttpExchange ex, int status, Map<String, Object> json) throws IOException {
        byte[] body = (Json.write(json) + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        return status;
    }

    private static int notAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        return respond(ex, 405, Json.obj("error", "method not allowed; allowed: " + allow));
    }

    /** The body, or null if it is longer than {@link #MAX_BODY}. */
    private static byte[] readBody(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BODY + 1);
        return bytes.length > MAX_BODY ? null : bytes;
    }

    private static Object parseQuietly(byte[] body) {
        try {
            String text = alang.util.Utf8.decode(body);
            return text == null ? null : Json.parse(text);
        } catch (Json.ParseException e) {
            return null;
        }
    }

    /** For the tests: the ids of the open sessions. */
    List<String> sessionIds() {
        return new ArrayList<>(sessions.keySet());
    }
}
