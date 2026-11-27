package alang.cli;

import static org.junit.jupiter.api.Assertions.*;

import alang.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Host fns from the command line: `--hosts` commands that answer the calls, the calls a report records, `--replay` of
 * them, the failures each becomes, and `fuzz` giving the replies.
 */
class HostsTest {
    @TempDir
    Path dir;

    private record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
            .run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static final String SHOP = """
        /// The price of an item in cents, or Option.None when it is not sold.
        host fn price(sku: str[8]) -> Option<i64>;

        /// The total of the items that are sold.
        tool fn total(skus: list[str[8]; 4]) -> i64 {
            var sum: i64 = 0;
            for s in skus {
                sum += unwrap_or(price(s), 0);
            }
            return sum;
        }

        fn main() {
            print(total(["tea", "cake"]));
        }
        """;

    /**
     * A host command, as a single-file Java program run by this JVM's launcher, so that it runs wherever the tests
     * do: it answers 350 for "tea", fails for "fail", answers a str for "bad" and null for anything else.
     */
    private static final String HOST = """
        public class Host {
            public static void main(String[] args) throws Exception {
                String in = new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!in.startsWith("{\\"fn\\": \\"price\\", \\"args\\": {\\"sku\\": ")) {
                    System.err.println("unexpected call " + in);
                    System.exit(4);
                }
                if (in.contains("\\"tea\\"")) System.out.println("350");
                else if (in.contains("\\"fail\\"")) {
                    System.err.println("the price list is down");
                    System.exit(3);
                } else if (in.contains("\\"bad\\"")) System.out.println("\\"cheap\\"");
                else System.out.println("null");
            }
        }
        """;

    private Path program() throws IOException {
        write("Host.java", HOST);
        String java = ProcessHandle.current().info().command().orElse("java");
        write("hosts.json", Json.write(Json.obj("price", Json.obj("command", List.of(java, "Host.java"), "timeout", 60))));
        return write("shop.al", SHOP);
    }

    private static Map<?, ?> report(Result r) {
        return (Map<?, ?>) Json.parse(r.out());
    }

    @Test
    void commandsAnswerAndEveryCallIsRecorded() throws IOException {
        Path p = program();
        String hosts = dir.resolve("hosts.json").toString();
        Result r = run("call", p.toString(), "total", "{\"skus\": [\"tea\", \"gold\"]}", "--hosts", hosts);
        assertEquals(0, r.code(), r.out() + r.err());
        Map<?, ?> j = report(r);
        assertEquals(List.of("ok", "result", "host_calls", "stdout", "steps"), List.copyOf(j.keySet()));
        assertEquals(350, ((Number) j.get("result")).intValue());
        assertEquals("[{\"fn\": \"price\", \"args\": {\"sku\": \"tea\"}, \"result\": 350}, "
            + "{\"fn\": \"price\", \"args\": {\"sku\": \"gold\"}, \"result\": null}]", Json.write(j.get("host_calls")));

        // The report answers the same calls again, without the command, and gives the same report.
        Path recording = write("recording.json", r.out());
        Result again = run("call", p.toString(), "total", "{\"skus\": [\"tea\", \"gold\"]}", "--replay", recording.toString());
        assertEquals(r.out(), again.out());

        // Another call than the one recorded gets no answer.
        Result other = run("call", p.toString(), "total", "{\"skus\": [\"cake\"]}", "--replay", recording.toString());
        Map<?, ?> error = (Map<?, ?>) report(other).get("error");
        assertEquals("R0015", error.get("code"));
        assertEquals("host fn `price` failed: call 1 is price({\"sku\": \"cake\"}), but the recording has "
            + "price({\"sku\": \"tea\"})", error.get("message"));

        Result main = run("run", p.toString(), "--hosts", hosts);
        assertEquals("350\n", main.out(), main.err());
    }

    @Test
    void aFailingCommandOrABadReplyTraps() throws IOException {
        Path p = program();
        String hosts = dir.resolve("hosts.json").toString();
        Map<?, ?> failed = (Map<?, ?>) report(run("call", p.toString(), "total", "{\"skus\": [\"fail\"]}", "--hosts", hosts))
            .get("error");
        assertEquals("R0015", failed.get("code"));
        // What it wrote on its standard error is the reason (after any notice of the JVM's own).
        String message = (String) failed.get("message");
        assertTrue(message.contains("Host.java` exited with status 3: ") && message.endsWith("the price list is down"), message);

        Map<?, ?> bad = report(run("call", p.toString(), "total", "{\"skus\": [\"bad\"]}", "--hosts", hosts));
        assertEquals("host fn `price` replied with JSON that is not Option<i64>: reply: expected an integer",
            ((Map<?, ?>) bad.get("error")).get("message"));
        // The reply is recorded, so that it can be seen.
        assertEquals("[{\"fn\": \"price\", \"args\": {\"sku\": \"bad\"}, \"result\": \"cheap\"}]", Json.write(bad.get("host_calls")));

        // A host fn the file names no command for fails, as does a program run without a host.
        Path other = write("other.json", "{\"stock\": {\"command\": [\"nothing\"]}}");
        Map<?, ?> unnamed = (Map<?, ?>) report(run("call", p.toString(), "total", "{\"skus\": [\"tea\"]}", "--hosts",
            other.toString())).get("error");
        assertEquals("host fn `price` failed: --hosts " + other + " names no command for `price` (nor \"*\")", unnamed.get("message"));
        Map<?, ?> none = (Map<?, ?>) report(run("call", p.toString(), "total", "{\"skus\": [\"tea\"]}")).get("error");
        assertEquals("R0015", none.get("code"));
        assertEquals("[{\"fn\": \"price\", \"args\": {\"sku\": \"tea\"}, \"error\": \"nothing answers host fn `price` here: in a "
            + "test, bind it with `test \\\"...\\\" with price = some_fn { }`; for run, call or serve, pass --hosts FILE or "
            + "--replay FILE\"}]", Json.write(report(run("call", p.toString(), "total", "{\"skus\": [\"tea\"]}")).get("host_calls")));
    }

    @Test
    void aHostsFileThatIsNotOneIsAUsageError() throws IOException {
        Path p = write("shop.al", SHOP);
        Path empty = write("empty.json", "{}");
        Path noCommand = write("bad.json", "{\"price\": {\"run\": \"x\"}}");
        Path badTimeout = write("timeout.json", "{\"price\": {\"command\": [\"x\"], \"timeout\": 0}}");
        for (Path f : List.of(empty, noCommand, badTimeout)) {
            Result r = run("run", p.toString(), "--hosts", f.toString());
            assertEquals(2, r.code(), f + ": " + r.out() + r.err());
            assertTrue(r.err().contains("--hosts " + f), r.err());
        }
        Result both = run("run", p.toString(), "--hosts", empty.toString(), "--replay", empty.toString());
        assertEquals(2, both.code());
        assertTrue(both.err().contains("--hosts and --replay cannot be combined"), both.err());
        Result notForTests = run("test", p.toString(), "--hosts", empty.toString());
        assertEquals(2, notForTests.code(), notForTests.err());
    }

    @Test
    void fuzzRepliesWithEveryValueOfTheResultType() throws IOException {
        Path p = write("shop.al", SHOP);
        Result r = run("fuzz", p.toString(), "--json");
        assertEquals(1, r.code(), r.out());
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) report(r).get("entries")).get(0);
        Map<?, ?> trap = (Map<?, ?>) ((List<?>) tool.get("traps")).get(0);
        assertEquals("R0001", trap.get("code"));
        // Prices the fuzzer chose added up past the largest i64, or below the smallest.
        List<?> calls = (List<?>) trap.get("host_calls");
        String last = Json.write(((Map<?, ?>) calls.get(calls.size() - 1)).get("result"));
        assertTrue(Set.of("9223372036854775807", "-9223372036854775808").contains(last), last);
    }

    @Test
    void aReplyThatBreaksTheHostFnsEnsuresIsTheHostsFault() throws IOException {
        Path p = write("shop.al", SHOP.replace("host fn price(sku: str[8]) -> Option<i64>;", """
            host fn price(sku: str[8]) -> Option<i64>
                ensures unwrap_or(result, 0) >= 0 and unwrap_or(result, 0) < 1000000;"""));
        Result r = run("fuzz", p.toString());
        assertEquals(0, r.code(), r.out());
    }
}
