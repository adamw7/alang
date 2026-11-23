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
import java.security.*;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** `alang serve --http --tls-cert FILE --tls-key FILE`: HTTPS, TLS 1.3 only. */
class HttpTransportTlsTest {
    private static final String PROGRAM = """
        /// Adds two numbers.
        tool fn add(a: i64, b: i64) -> i64 {
            return a + b;
        }
        """;

    private static final String TOKEN = "s3cret-token-0123456789";

    /** A certificate for 127.0.0.1 and localhost and its private key, in PEM, and a trust store holding the certificate. */
    private record Pair(String cert, String key, KeyStore trust) {}

    @TempDir
    static Path dir;

    private static Pair rsa;
    private static Pair rsa2;
    private static Pair ec;
    private static Pair ed25519;

    @BeforeAll
    static void makeKeys() throws Exception {
        rsa = pair("rsa", "-keyalg", "RSA", "-keysize", "2048");
        rsa2 = pair("rsa2", "-keyalg", "RSA", "-keysize", "2048");
        ec = pair("ec", "-keyalg", "EC", "-groupname", "secp256r1");
        ed25519 = pair("ed25519", "-keyalg", "Ed25519");
    }

    /** A self-signed certificate and its key, made with the JDK's keytool, so no private key is kept in the repository. */
    private static Pair pair(String name, String... keyArgs) throws Exception {
        Path store = dir.resolve(name + ".p12");
        char[] password = "changeit".toCharArray();
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-alias", "server", "-keystore", store.toString(), "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=localhost",
            "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2"));
        cmd.addAll(List.of(keyArgs));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "keytool did not finish");
        assertEquals(0, p.exitValue(), output);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, password);
        }
        Certificate cert = ks.getCertificate("server");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", cert);
        return new Pair(pem("CERTIFICATE", cert.getEncoded()), pem("PRIVATE KEY", ks.getKey("server", password).getEncoded()), trust);
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
            + "\n-----END " + label + "-----\n";
    }

    private static McpServer server() {
        Frontend.Result r = Frontend.compile(new Source("add.al", PROGRAM), Checker.DEFAULT_MAX_STACK);
        assertTrue(r.ok(), () -> r.diags().renderText());
        return new McpServer(r.diags().source(), r.program(), 0, new Watchdog(0), 1, 4);
    }

    /** A client that trusts {@code pair}'s certificate and offers only {@code protocols}. */
    private static HttpClient client(Pair pair, String... protocols) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(pair.trust());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        HttpClient.Builder b = HttpClient.newBuilder().sslContext(ctx).connectTimeout(Duration.ofSeconds(10));
        if (protocols.length > 0) b.sslParameters(new SSLParameters(null, protocols));
        return b.build();
    }

    private static HttpResponse<String> send(HttpClient client, String url, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        b = body == null ? b.GET() : b.POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Runs {@code body} against a server on 127.0.0.1 serving HTTPS with {@code pair}; the log is passed on. */
    private interface Body {
        void run(int port, ByteArrayOutputStream log) throws Exception;
    }

    private static void serving(Pair pair, Body body) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        HttpTransport t = new HttpTransport(server(), new HttpTransport.Config("127.0.0.1", 0, TOKEN, List.of(), pair.cert(),
            pair.key()), new PrintStream(log, true, StandardCharsets.UTF_8));
        int port = t.start();
        try {
            body.run(port, log);
        } finally {
            t.stop();
        }
    }

    @Test
    void servesMcpOverTls13() throws Exception {
        serving(rsa, (port, log) -> {
            HttpClient client = client(rsa);
            String base = "https://127.0.0.1:" + port;
            HttpResponse<String> health = send(client, base + "/health", null);
            assertEquals(200, health.statusCode());
            assertEquals("TLSv1.3", health.sslSession().orElseThrow().getProtocol());

            String[] auth = {"Authorization", "Bearer " + TOKEN, "Content-Type", "application/json"};
            HttpResponse<String> init = send(client, base + "/mcp", "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", "
                + "\"params\": {\"protocolVersion\": \"2025-06-18\"}}", auth);
            assertEquals(200, init.statusCode(), init.body());
            String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
            List<String> h = new ArrayList<>(List.of(auth));
            h.addAll(List.of("Mcp-Session-Id", session));
            HttpResponse<String> call = send(client, base + "/mcp", "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/call\", "
                + "\"params\": {\"name\": \"add\", \"arguments\": {\"a\": 2, \"b\": 3}}}", h.toArray(String[]::new));
            assertEquals(200, call.statusCode(), call.body());
            assertTrue(call.body().contains("\"structuredContent\":{\"result\":5}") || call.body().contains("\"result\": 5"),
                call.body());
            assertEquals(401, send(client, base + "/mcp", "{}").statusCode());

            Map<?, ?> listening = (Map<?, ?>) Json.parse(log.toString(StandardCharsets.UTF_8).split("\n")[0]);
            assertEquals("listening", listening.get("event"));
            assertEquals("TLSv1.3", listening.get("tls"));
            assertNull(listening.get("warning"));
        });
    }

    @Test
    void servesEcAndEd25519Keys() throws Exception {
        for (Pair pair : List.of(ec, ed25519)) {
            serving(pair, (port, log) -> {
                HttpResponse<String> r = send(client(pair), "https://127.0.0.1:" + port + "/health", null);
                assertEquals(200, r.statusCode());
                assertEquals("TLSv1.3", r.sslSession().orElseThrow().getProtocol());
            });
        }
    }

    @Test
    void refusesOlderTlsAndPlainHttp() throws Exception {
        serving(rsa, (port, log) -> {
            HttpClient tls12 = client(rsa, "TLSv1.2");
            assertThrows(IOException.class, () -> send(tls12, "https://127.0.0.1:" + port + "/health", null));
            HttpClient plain = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            assertThrows(IOException.class, () -> send(plain, "http://127.0.0.1:" + port + "/health", null));
            // The server is still up for a client that speaks TLS 1.3.
            assertEquals(200, send(client(rsa, "TLSv1.3"), "https://127.0.0.1:" + port + "/health", null).statusCode());
        });
    }

    @Test
    void readsAChainWithTextAroundIt() {
        // As `openssl s_client -showcerts` or some CAs write it: notes before each block, intermediates after the leaf.
        String chain = "subject=CN=localhost\n" + rsa.cert() + "\nissuer=CN=intermediate\n" + ec.cert();
        assertNotNull(HttpTransport.tls(chain, rsa.key()));
    }

    private static String refusal(String cert, String key) {
        return assertThrows(IllegalArgumentException.class, () -> HttpTransport.tls(cert, key)).getMessage();
    }

    @Test
    void saysWhyACertificateOrKeyCannotServe() {
        assertTrue(refusal(rsa.cert(), rsa2.key()).contains("is not the private key of the first certificate"));
        assertTrue(refusal(rsa.cert(), ec.key()).contains("not a valid RSA private key"));
        assertTrue(refusal("", rsa.key()).contains("no \"-----BEGIN CERTIFICATE-----\" block"));
        assertTrue(refusal(rsa.key(), rsa.key()).contains("no \"-----BEGIN CERTIFICATE-----\" block"));
        assertTrue(refusal(pem("CERTIFICATE", new byte[] {1, 2, 3}), rsa.key()).startsWith("--tls-cert: not a valid PEM certificate"));
        assertTrue(refusal(rsa.cert(), "").contains("openssl pkey -in KEY -out key.pem"));
        assertTrue(refusal(rsa.cert(), pem("ENCRYPTED PRIVATE KEY", new byte[] {1})).contains("the key is encrypted"));
        assertTrue(refusal(rsa.cert(), pem("RSA PRIVATE KEY", new byte[] {1})).contains("older RSA PRIVATE KEY format"));
        assertTrue(refusal(rsa.cert(), pem("EC PRIVATE KEY", new byte[] {1})).contains("older EC PRIVATE KEY format"));

        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new HttpTransport(server(),
            new HttpTransport.Config("127.0.0.1", 0, null, List.of(), rsa.cert(), null), sink));
    }

    @Test
    void warnsOfAPlainTextTokenBeyondThisMachine() throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        HttpTransport t = new HttpTransport(server(), new HttpTransport.Config("0.0.0.0", 0, TOKEN, List.of()),
            new PrintStream(log, true, StandardCharsets.UTF_8));
        t.start();
        t.stop();
        Map<?, ?> listening = (Map<?, ?>) Json.parse(log.toString(StandardCharsets.UTF_8).split("\n")[0]);
        assertEquals(false, listening.get("tls"));
        assertTrue(((String) listening.get("warning")).contains("plain text"), listening.toString());
    }

    @Test
    void cliRefusesBadTlsOptions() throws IOException {
        Path prog = dir.resolve("add.al");
        Files.writeString(prog, PROGRAM);
        Path cert = Files.writeString(dir.resolve("cert.pem"), rsa.cert());
        Path key = Files.writeString(dir.resolve("key.pem"), rsa.key());
        Path wrongKey = Files.writeString(dir.resolve("wrong.pem"), rsa2.key());
        String p = prog.toString();
        assertEquals(2, cli("serve", p, "--tls-cert", cert.toString(), "--tls-key", key.toString()));
        assertEquals(2, cli("serve", p, "--http", "0", "--tls-cert", cert.toString()));
        assertEquals(2, cli("serve", p, "--http", "0", "--tls-key", key.toString()));
        assertEquals(2, cli("serve", p, "--http", "0", "--tls-cert", dir.resolve("missing.pem").toString(), "--tls-key", key.toString()));
        assertEquals(2, cli("serve", p, "--http", "0", "--tls-cert", cert.toString(), "--tls-key", wrongKey.toString()));
    }

    private static int cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        return new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
            new ByteArrayInputStream(new byte[0])).run(args);
    }
}
