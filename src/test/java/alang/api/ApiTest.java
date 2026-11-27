package alang.api;

import static org.junit.jupiter.api.Assertions.*;

import alang.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The embedding API: compile, list the tools, call them, as `alang check`, `tools` and `call` do. */
class ApiTest {
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
        tool fn hello(name: str[8], punct: str[1] = "!") {
            print("hello ", name, punct);
        }

        /// Adds up 0..n.
        tool fn spin(n: i64) -> i64 {
            var s: i64 = 0;
            for i in 0..n {
                s = wrapping_add(s, i);
            }
            return s;
        }

        fn helper() -> i64 {
            return 1;
        }
        """;

    private static Program program() {
        Compilation c = Alang.compile("bill.al", PROGRAM);
        assertTrue(c.ok(), c::diagnosticsText);
        return c.program();
    }

    @Test
    void listsTheToolsWithTheirSchemas() {
        Program p = program();
        assertEquals("bill.al", p.path());
        assertEquals(List.of("split_bill", "hello", "spin"), p.tools().stream().map(Tool::name).toList());
        Tool bill = p.tool("split_bill").orElseThrow();
        assertTrue(bill.description().startsWith("Splits a bill evenly"), bill.description());
        Map<?, ?> schema = (Map<?, ?>) Json.parse(bill.inputSchemaJson());
        assertEquals("object", schema.get("type"));
        Map<?, ?> people = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("people");
        assertEquals(1, ((Number) people.get("minimum")).intValue());
        assertNotNull(bill.outputSchemaJson());
        assertNull(p.tool("hello").orElseThrow().outputSchemaJson());
        assertTrue(p.tool("helper").isEmpty());
        List<?> all = (List<?>) Json.parse(p.toolsJson());
        assertEquals(3, all.size());
        assertEquals("split_bill", ((Map<?, ?>) all.get(0)).get("name"));
        assertTrue(((Map<?, ?>) all.get(0)).containsKey("input_schema"));
    }

    @Test
    void callsATool() {
        CallResult r = program().call("split_bill", "{\"total_cents\": 1000, \"people\": 3}");
        assertTrue(r.ok(), r::toJson);
        assertEquals("[334, 333, 333]", r.resultJson());
        assertNull(r.errorCode());
        assertEquals("", r.stdout());
        assertEquals(16, r.steps());
        assertEquals("{\"ok\": true, \"result\": [334, 333, 333], \"stdout\": \"\", \"steps\": 16}", r.toJson());
    }

    @Test
    void capturesOutputAndUsesDefaults() {
        CallResult r = program().call("hello", "{\"name\": \"ann\"}");
        assertTrue(r.ok(), r::toJson);
        assertNull(r.resultJson());
        assertEquals("hello ann!\n", r.stdout());
    }

    @Test
    void reportsEveryFailureAsAResult() {
        Program p = program();
        assertEquals("bad_tool", p.call("nope", "{}").errorCode());
        assertEquals("bad_tool", p.call("helper", "{}").errorCode());
        assertEquals("bad_json", p.call("spin", "{").errorCode());
        CallResult bad = p.call("spin", "{\"n\": \"x\"}");
        assertEquals("bad_arguments", bad.errorCode());
        assertNotNull(bad.errorMessage());
        CallResult trap = p.call("split_bill", "{\"total_cents\": 1, \"people\": 0}");
        assertFalse(trap.ok());
        assertEquals("R0006", trap.errorCode());
        CallResult budget = p.call("spin", "{\"n\": 1000000}", CallOptions.defaults().maxSteps(100));
        assertEquals("R0011", budget.errorCode());
    }

    @Test
    void stopsACallAtItsTimeLimit() {
        CallResult r = program().call("spin", "{\"n\": 1000000000000000}",
            CallOptions.defaults().maxSteps(0).timeoutMillis(50));
        assertEquals("timeout", r.errorCode(), r::toJson);
    }

    @Test
    void cancelsACall() throws Exception {
        Program p = program();
        Cancellation before = Cancellation.create();
        before.cancel();
        assertTrue(before.isCancelled());
        CallResult early = p.call("spin", "{\"n\": 10}", CallOptions.defaults().cancellation(before));
        assertEquals("cancelled", early.errorCode(), early::toJson);

        Cancellation during = Cancellation.create();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CallResult> f = pool.submit(() -> p.call("spin", "{\"n\": 1000000000000000}",
                CallOptions.defaults().maxSteps(0).timeoutMillis(0).cancellation(during)));
            during.cancel();
            assertEquals("cancelled", f.get(30, TimeUnit.SECONDS).errorCode());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void callsFromManyThreadsGiveTheSameResults() throws Exception {
        Program p = program();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                int people = 1 + i % 4;
                results.add(pool.submit(() -> p.call("split_bill", "{\"total_cents\": 1001, \"people\": " + people + "}").toJson()));
            }
            for (int i = 0; i < results.size(); i++) {
                String expected = p.call("split_bill", "{\"total_cents\": 1001, \"people\": " + (1 + i % 4) + "}").toJson();
                assertEquals(expected, results.get(i).get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reportsDiagnostics() {
        Compilation c = Alang.compile("bad.al", "fn main() {\n    let x = 5;\n}\n");
        assertFalse(c.ok());
        Diagnostic d = c.errors().get(0);
        assertEquals("E0219", d.code());
        assertEquals(Severity.ERROR, d.severity());
        assertEquals("bad.al", d.file());
        assertEquals(2, d.line());
        assertNotNull(d.hint());
        assertTrue(c.diagnosticsText().contains("E0219"), c.diagnosticsText());
        assertEquals("E0219", ((Map<?, ?>) ((List<?>) Json.parse(c.diagnosticsJson())).get(0)).get("code"));
        CompileException e = assertThrows(CompileException.class, c::program);
        assertEquals(c.diagnostics(), e.diagnostics());
    }

    @Test
    void enforcesTheMemoryBudget() {
        String big = "fn main() {\n    var xs: list[i64; 100000] = [];\n    push(mut xs, 1);\n    print(len(xs));\n}\n";
        assertTrue(Alang.compile("big.al", big).ok());
        Compilation c = Alang.compile("big.al", big, CompileOptions.defaults().maxStackBytes(1024));
        assertFalse(c.ok());
        assertEquals("E0402", c.errors().get(0).code());
    }

    @Test
    void compilesAFileWithItsImports(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("lib"));
        Files.writeString(dir.resolve("lib/twice.al"), "fn twice(x: i64) -> i64 {\n    return x * 2;\n}\n");
        Files.writeString(dir.resolve("main.al"), "import \"lib/twice.al\";\n\n/// Doubles.\ntool fn double(x: i64) -> i64 {\n"
            + "    return twice(x);\n}\n");
        Compilation c = Alang.compile(dir.resolve("main.al"));
        assertTrue(c.ok(), c::diagnosticsText);
        assertEquals("42", c.program().call("double", "{\"x\": 21}").resultJson());
        Compilation missing = Alang.compile("x.al", "import \"nope.al\";\n");
        assertEquals("E0701", missing.errors().get(0).code());
    }

    @Test
    void carriesTheVersionAndTheGuide() {
        assertFalse(Alang.version().isBlank());
        assertTrue(Alang.agentsGuide().contains("ALang"));
    }

    @Test
    void validatesOptions() {
        assertThrows(IllegalArgumentException.class, () -> CallOptions.defaults().maxSteps(-1));
        assertThrows(IllegalArgumentException.class, () -> CallOptions.defaults().timeoutMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> CompileOptions.defaults().maxStackBytes(-1));
        CallOptions o = CallOptions.defaults().maxSteps(5).timeoutMillis(7);
        assertEquals(5, o.maxSteps());
        assertEquals(7, o.timeoutMillis());
        assertNull(o.cancellation());
    }

    private static final String SHOP = """
        /// The price of an item in cents.
        host fn price(sku: str[8]) -> i64;

        /// Prices an item.
        tool fn quote(sku: str[8]) -> i64 {
            return price(sku) * 2;
        }
        """;

    @Test
    void aHostAnswersTheHostFnsAndTheCallsAreRecorded() {
        Program p = Alang.compile("shop.al", SHOP).program();
        assertTrue(p.tool("quote").orElseThrow().description().endsWith("Asks the host (price), so it may reach outside "
            + "data, and a repeated call may differ."));
        Host host = (fn, args) -> {
            assertEquals("price", fn);
            assertEquals("{\"sku\": \"tea\"}", args);
            return "350";
        };
        CallResult r = p.call("quote", "{\"sku\": \"tea\"}", CallOptions.defaults().host(host));
        assertEquals("700", r.resultJson(), r::toJson);
        assertEquals("[{\"fn\": \"price\", \"args\": {\"sku\": \"tea\"}, \"result\": 350}]", r.hostCallsJson());

        // The recording answers the same call again; another call gets no answer.
        CallResult again = p.call("quote", "{\"sku\": \"tea\"}", CallOptions.defaults().host(Host.replay(r.toJson())));
        assertEquals(r.toJson(), again.toJson());
        CallResult other = p.call("quote", "{\"sku\": \"jam\"}", CallOptions.defaults().host(Host.replay(r.hostCallsJson())));
        assertEquals("R0015", other.errorCode());
        assertThrows(IllegalArgumentException.class, () -> Host.replay("3"));
    }

    @Test
    void aHostThatThrowsOrAnswersBadlyFailsOnlyTheCall() {
        Program p = Alang.compile("shop.al", SHOP).program();
        CallResult thrown = p.call("quote", "{\"sku\": \"tea\"}", CallOptions.defaults().host((fn, args) -> {
            throw new IllegalStateException("no price list");
        }));
        assertEquals("R0015", thrown.errorCode());
        assertEquals("host fn `price` failed: no price list", thrown.errorMessage());
        assertEquals("[{\"fn\": \"price\", \"args\": {\"sku\": \"tea\"}, \"error\": \"no price list\"}]", thrown.hostCallsJson());
        assertEquals("R0015", p.call("quote", "{\"sku\": \"tea\"}", CallOptions.defaults().host((fn, args) -> "{")).errorCode());
        assertEquals("R0016", p.call("quote", "{\"sku\": \"tea\"}", CallOptions.defaults().host((fn, args) -> "\"cheap\""))
            .errorCode());
        assertEquals("R0015", p.call("quote", "{\"sku\": \"tea\"}").errorCode());
        assertEquals("[]", p.call("quote", "{\"sku\": 1}").hostCallsJson());
    }
}
