package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.Harness;
import alang.check.Nesting;
import alang.parse.Parser;
import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** The command line: tool calls with malformed JSON, and the nesting limits (E0404). */
class CliTest {
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

    /**
     * Runs the command on a thread with a stack of {@code kilobytes}. The default is 1 MB; the JVM
     * itself uses about 100 KB of it before the command starts.
     */
    private static Result runOnStack(int kilobytes, String... args) throws InterruptedException {
        Result[] result = new Result[1];
        Thread t = new Thread(null, () -> result[0] = run(args), "alang-test", kilobytes * 1024L);
        t.start();
        t.join(Harness.TEST_TIMEOUT.toMillis());
        assertFalse(t.isAlive(), "the command did not finish within " + Harness.TEST_TIMEOUT);
        return result[0];
    }

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void jsonUnicodeEscapes() {
        assertEquals("h\u00e9y", Json.parse("\"h\\u00e9y\""));
        assertEquals("\uD83D\uDE00", Json.parse("\"\\ud83d\\ude00\""));
        for (String bad : new String[] {"\"\\u00\"", "\"\\uZZZZ\"", "\"\\u+041\"", "\"\\u-041\"", "\"\\u\"", "\"\\u12"}) {
            assertThrows(Json.ParseException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void malformedEscapeInToolArgumentsIsAJsonError() throws IOException {
        Path p = write("t.al", "/// Echo.\ntool fn echo(s: str[8]) -> str[8] { return s; }\n");
        Result r = run("call", p.toString(), "echo", "{\"s\": \"\\uZZZZ\"}");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_json\""), r.out());
        assertEquals("", r.err());
    }

    @Test
    void undecodableArgumentIsRefusedOnEveryHost() throws IOException {
        Path p = write("t.al", "/// Echo.\ntool fn echo(s: str[8]) -> str[8] { return s; }\n");
        Result r = run("call", p.toString(), "echo", "{\"s\": \"h\uFFFDy\"}");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_arguments\""), r.out());
        assertTrue(r.out().contains("pass the arguments on stdin"), r.out());
        // The same character written as a JSON escape is ordinary input.
        Result escaped = run("call", p.toString(), "echo", "{\"s\": \"h\\ufffdy\"}");
        assertEquals(0, escaped.code(), escaped.out() + escaped.err());
    }

    @Test
    void deeplyNestedJsonIsAJsonError() throws Exception {
        int max = Json.MAX_DEPTH;
        assertTrue(Json.parse("[".repeat(max) + "]".repeat(max)) instanceof List<?>);
        assertThrows(Json.ParseException.class, () -> Json.parse("[".repeat(max + 1) + "]".repeat(max + 1)));
        Path p = write("t.al", "/// Echo.\ntool fn echo(s: str[8]) -> str[8] { return s; }\n");
        // Reading stops at the limit, so the command needs no more stack than for valid arguments.
        int deep = 100_000;
        Result r = runOnStack(512, "call", p.toString(), "echo", "{\"s\": " + "[".repeat(deep) + "]".repeat(deep) + "}");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_json\""), r.out());
        assertTrue(r.out().contains("nested more than " + max + " levels deep"), r.out());
    }

    /** Programs nested {@code n} levels deep in one way, and the largest n the limits accept. */
    private record Shape(String name, int limit, IntFunction<String> program) {}

    // The limits, in the terms of each shape. A function body is depth 1 and its statements depth 2;
    // the `{` of the function body is the first bracket.
    private static final int DEPTH = Nesting.MAX_DEPTH;
    private static final int BRACKETS = Parser.MAX_NESTING;
    private static final List<Shape> SHAPES = List.of(
        new Shape("parentheses", BRACKETS - 1,
            n -> "fn main() {\n    let x: i64 = " + "(".repeat(n) + "1" + ")".repeat(n) + ";\n    print(x);\n}\n"),
        new Shape("prefix operators", Math.min(BRACKETS - 1, DEPTH - 3),
            n -> "fn main() {\n    let y: i64 = 1;\n    let x: i64 = " + "- ".repeat(n) + "y;\n    print(x);\n}\n"),
        new Shape("operator chain", DEPTH - 3,
            n -> "fn main() {\n    let x: i64 = " + "1 + ".repeat(n) + "1;\n    print(x);\n}\n"),
        new Shape("blocks", Math.min(BRACKETS - 1, (DEPTH - 3) / 2),
            n -> "fn main() {\n    var x: i64 = 0;\n" + "if true {\n".repeat(n) + "x += 1;\n" + "}\n".repeat(n)
                + "    print(x);\n}\n"),
        new Shape("list types", Math.min(BRACKETS - 1, DEPTH - 3),
            n -> "fn main() {\n    let x: " + "list[".repeat(n) + "i64" + "; 1]".repeat(n) + " = [];\n    print(len(x));\n}\n"),
        new Shape("struct types", Nesting.MAX_TYPE_DEPTH - 2, CliTest::structChain),
        new Shape("calls", (DEPTH - 7) / 4, CliTest::callChain));

    /** Structs S0 to Sn, each holding the next; Sn holds an i64. A tool echoes an S0. */
    private static String structChain(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("struct S").append(i).append(" {\n    a: S").append(i + 1).append(",\n}\n");
        sb.append("struct S").append(n).append(" {\n    v: i64,\n}\n");
        sb.append("/// Returns its argument.\ntool fn same(s: S0) -> S0 {\n    return s;\n}\n");
        sb.append("fn main() {\n    let s").append(n).append(": S").append(n).append(" = S").append(n).append("(v: 7);\n");
        for (int i = n - 1; i >= 0; i--) {
            sb.append("    let s").append(i).append(": S").append(i).append(" = S").append(i).append("(a: s").append(i + 1)
                .append(");\n");
        }
        return sb.append("    print(s0);\n}\n").toString();
    }

    /** Functions g0 to gn, each returning the next one's result plus 1; main prints g0(). */
    private static String callChain(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("fn g").append(i).append("() -> i64 {\n    return g").append(i + 1).append("() + 1;\n}\n");
        sb.append("fn g").append(n).append("() -> i64 {\n    return 0;\n}\n");
        return sb.append("fn main() {\n    print(g0());\n}\n").toString();
    }

    @TestFactory
    Stream<DynamicTest> nestingLimits() {
        return SHAPES.stream().map(s -> Harness.dynamicTest(s.name(), () -> {
            // At the limit every command works, even on half the default stack.
            Path ok = write("ok.al", s.program().apply(s.limit()));
            for (String cmd : List.of("check", "run", "test")) {
                Result r = runOnStack(512, cmd, ok.toString());
                assertEquals(0, r.code(), cmd + " at the limit: " + r.err());
            }
            Result build = runOnStack(512, "build", ok.toString(), "-o", dir.resolve("ok.c").toString());
            assertEquals(0, build.code(), "build at the limit: " + build.err());
            if (s.name().equals("struct types")) {
                assertEquals(0, runOnStack(512, "tools", ok.toString()).code());
                String arg = "{\"s\": " + "{\"a\": ".repeat(s.limit()) + "{\"v\": 7}" + "}".repeat(s.limit()) + "}";
                Result call = runOnStack(512, "call", ok.toString(), "same", arg);
                assertTrue(call.out().startsWith("{\"ok\": true"), call.out() + call.err());
            }
            // One level deeper, or far deeper: E0404, found without recursion before anything else
            // recurses, so even a quarter of the default stack is enough.
            for (int n : new int[] {s.limit() + 1, 3000}) {
                Path deep = write("deep.al", s.program().apply(n));
                Result r = runOnStack(256, "check", deep.toString());
                assertEquals(1, r.code(), n + " levels: " + r.err());
                assertTrue(r.err().startsWith("error[E0404]"), n + " levels: " + r.err());
            }
        }));
    }

    @Test
    void explanationStatesTheLimits() {
        String text = alang.diag.Code.E0404.explanation;
        for (int limit : new int[] {Parser.MAX_NESTING, Nesting.MAX_DEPTH, Nesting.MAX_TYPE_DEPTH}) {
            assertTrue(text.contains("limited to " + limit + " levels"), text);
        }
    }
}
