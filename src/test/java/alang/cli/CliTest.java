package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line: tool calls with malformed JSON, the nesting limit, non-ASCII arguments, the memory
 * budget, captured output and arguments on stdin.
 */
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

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    /** "é" in UTF-8 as printf octal escapes, so the bytes do not depend on this JVM's charset. */
    private static final String E_ACUTE = "\\303\\251";

    private void sh(String command) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", command).directory(dir.toFile()).start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, command);
    }

    /**
     * Runs the command line in a new JVM under the POSIX locale, where Java on Linux decodes arguments
     * as ASCII and replaces every other byte with U+FFFD (macOS always decodes UTF-8). The shell builds
     * the arguments, so their bytes are exact.
     */
    private Result runInPosixLocale(String shellArgs) throws Exception {
        return runInLocale("C", shellArgs);
    }

    private Result runInLocale(String locale, String shellArgs) throws Exception {
        return runProcess(locale, shellArgs, new byte[0], false);
    }

    /**
     * Runs the command line in a new JVM with {@code stdin} as its input. With {@code keepStdinOpen},
     * stdin never ends, like that of an agent harness that does not close it: a command that reads it
     * never exits, and the test fails on its time limit.
     */
    private Result runProcess(String locale, String shellArgs, byte[] stdin, boolean keepStdinOpen) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "exec \"$ALANG_JAVA\" -cp \"$ALANG_CP\" alang.cli.Main " + shellArgs)
            .directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("LC_") || k.equals("LANG") || k.equals("JAVA_TOOL_OPTIONS"));
        env.put("LC_ALL", locale);
        env.put("ALANG_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        env.put("ALANG_CP", System.getProperty("java.class.path"));
        Process p = pb.start();
        try {
            OutputStream in = p.getOutputStream();
            in.write(stdin);
            if (keepStdinOpen) in.flush();
            else in.close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the JVM did not exit");
            return new Result(p.exitValue(), out, err);
        } finally {
            p.destroyForcibly();
        }
    }

    /** The argument was read as typed (the expected output), or refused as a usage error; never changed. */
    private static void assertReadOrRefused(Result r, String expected) {
        if (r.code() == 2) {
            assertTrue(r.err().contains("could not be decoded in this host locale"), r.err());
        } else {
            assertEquals(0, r.code(), r.err());
            assertTrue(r.out().contains(expected), () -> "expected " + expected + " in: " + r.out());
        }
    }

    @Test
    void nonAsciiArgumentsInThePosixLocaleAreReadOrRefused() throws Exception {
        write("size.al", "tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n");
        write("t.al", "test \"caf\u00e9\" {\n    assert true;\n}\n");

        // `call`: len("é") is 2 bytes, never the 6 bytes of two U+FFFD.
        Result r = runInPosixLocale("call size.al size \"$(printf '{\"text\": \"" + E_ACUTE + "\"}')\"");
        assertTrue(r.out().contains("\"result\": 2") || r.out().contains("\"code\": \"bad_arguments\""), r.out() + r.err());

        // `--filter`: a changed filter used to match no test, and the run passed with exit code 0.
        assertReadOrRefused(runInPosixLocale("test t.al --filter \"$(printf 'caf" + E_ACUTE + "')\""),
            "1 test(s): 1 passed");

        // A non-ASCII file name used to crash with an InvalidPathException.
        sh("cp size.al \"$(printf 'caf" + E_ACUTE + ".al')\"");
        assertReadOrRefused(runInPosixLocale("check \"$(printf 'caf" + E_ACUTE + ".al')\""), "1 function(s)");
    }

    @Test
    void invalidUtf8ArgumentsAreRefusedInAnyLocale() throws Exception {
        write("size.al", "tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n");
        // \\377 is never valid UTF-8, so every JVM decodes it as U+FFFD, on Linux and macOS alike.
        Result r = runInLocale("C.UTF-8", "check \"$(printf 'size\\377.al')\"");
        assertEquals(2, r.code(), r.out() + r.err());
        assertTrue(r.err().contains("could not be decoded in this host locale"), r.err());
        r = runInLocale("C.UTF-8", "call size.al size \"$(printf '{\"text\": \"\\377\"}')\"");
        assertTrue(r.out().contains("\"code\": \"bad_arguments\""), r.out() + r.err());
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
    void tooDeeplyNestedProgramsGetE0404() throws Exception {
        // Each of these used to exhaust the stack somewhere between a few hundred and a few thousand
        // levels, depending on the machine. Now the limit is fixed and checked before anything recurses.
        String plus = "fn main() { let x: i64 = " + "1 + ".repeat(3000) + "1; print(x); }\n";
        StringBuilder consts = new StringBuilder("fn main() { print(C0); }\n");
        StringBuilder types = new StringBuilder("fn main() { print(1); }\n");
        for (int i = 0; i < 3000; i++) {
            consts.append("const C").append(i).append(": i64 = C").append(i + 1).append(" + 1;\n");
            types.append("struct S").append(i).append(" {\n    next: S").append(i + 1).append(",\n}\n");
        }
        consts.append("const C3000: i64 = 0;\n");
        types.append("struct S3000 {\n    v: i64,\n}\n");
        for (String program : new String[] {plus, consts.toString(), types.toString(), callChain(3000)}) {
            Result r = run("check", write("deep.al", program).toString());
            assertEquals(1, r.code(), r.err());
            assertTrue(r.err().startsWith("error[E0404]: "), r.err());
        }
    }

    /** main calls g0, which calls g1, and so on: main weighs 3, each g 2 and the last one 1. */
    private static String callChain(int length) {
        StringBuilder sb = new StringBuilder("fn main() { print(g0()); }\n");
        for (int i = 0; i < length; i++) {
            sb.append("fn g").append(i).append("() -> i64 { return g").append(i + 1).append("() + 1; }\n");
        }
        return sb.append("fn g").append(length).append("() -> i64 { return 0; }\n").toString();
    }

    /**
     * Programs right at the limit (the deepest-recursing constructs: parentheses, and calls in the
     * interpreter) are checked, run and compiled on this test's default thread stack; one more level
     * is E0404. This is what keeps the limit safe on the machines CI runs on.
     */
    @Test
    void programsAtTheNestingLimitWork() throws Exception {
        String parens127 = "fn main() { let x: i64 = " + "(".repeat(127) + "1" + ")".repeat(127) + "; print(x); }\n";
        String parens128 = "fn main() { let x: i64 = " + "(".repeat(128) + "1" + ")".repeat(128) + "; print(x); }\n";
        for (String[] ok : new String[][] {{parens127, "1"}, {callChain(62), "62"}}) {
            Path p = write("limit.al", ok[0]);
            Result r = run("run", p.toString());
            assertEquals(0, r.code(), r.err());
            assertEquals(ok[1] + "\n", r.out());
            assertEquals(0, run("build", p.toString(), "-o", dir.resolve("limit.c").toString()).code());
        }
        for (String tooDeep : new String[] {parens128, callChain(63)}) {
            Result r = run("check", write("deep.al", tooDeep).toString());
            assertTrue(r.err().startsWith("error[E0404]: "), r.err());
        }
    }

    @Test
    void deeplyNestedJsonArgumentsAreAJsonError() throws IOException {
        Path p = write("t.al", "/// Identity.\ntool fn id(x: i64) -> i64 { return x; }\n");
        Result r = run("call", p.toString(), "id", "{\"x\": " + "[".repeat(5000) + "]".repeat(5000) + "}");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_json\""), r.out());
    }

    @Test
    void callWithoutArgumentsDoesNotReadStdin() throws Exception {
        // `call` used to read its arguments from stdin whenever argv had none, so a call to a tool
        // without parameters waited forever on a caller that left stdin open.
        write("one.al", "tool fn one() -> i64 {\n    return 1;\n}\n");
        Result r = runProcess("C.UTF-8", "call one.al one", new byte[0], true);
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("\"result\": 1"), r.out());
    }

    @Test
    void dashReadsTheArgumentsFromStdinAsStrictUtf8() throws Exception {
        write("size.al", "tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n");
        Result r = runProcess("C", "call size.al size -", "{\"text\": \"\u00e9\"}".getBytes(StandardCharsets.UTF_8), false);
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("\"result\": 2"), r.out());
        // An invalid byte used to become U+FFFD, 3 bytes long, and the tool ran on text nobody sent.
        r = runProcess("C", "call size.al size -", new byte[] {'{', '"', 't', 'e', 'x', 't', '"', ':', '"', (byte) 0xFF, '"', '}'}, false);
        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("\"code\": \"bad_arguments\"") && r.out().contains("not valid UTF-8"), r.out());
    }

    @Test
    void jsonIsReadStrictly() {
        String[] valid = {"0", "-0", "12", "0.5", "-1.5e-3", "1E+5", " \t\n\r1 \r\n", "\"\\ud83d\\ude00\"", "\"\uD83D\uDE00\"",
            "\"\\u0000\""};
        for (String text : valid) Json.parse(text);
        String[] invalid = {
            "01", "-01", "-", "1.", ".5", "1e", "1e+", "+1", "0x10",
            "\u0663", "-\u0663", "1\u0663",             // non-ASCII digits: BigInteger reads them as numbers
            "\f1", "1\u00a0", "\u20281",               // whitespace that JSON does not allow
            "\"a\u0001\"", "\"a\nb\"",                 // raw control characters in a string
            "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"", "\"\\ud800\\u0041\"",  // escapes of lone surrogates
            "\"\uD800\"", "\"\uDC00\"",                // lone surrogates as characters
        };
        for (String text : invalid) assertThrows(Json.ParseException.class, () -> Json.parse(text), text);
        assertEquals(new BigInteger("-0"), Json.parse("-0"));
    }

    @Test
    void floatsInToolCalls() throws IOException {
        Path p = write("f.al", """
            tool fn tenth() -> f32 {
                return 0.1;
            }

            tool fn ratio(a: f64, b: f64) -> list[f64; 2] {
                return [1.0, a / b];
            }

            tool fn narrow(x: f32) -> f32 {
                return x;
            }
            """);
        // The shortest decimal of the f32, not of the double it widens to (0.10000000149011612).
        assertEquals("{\"ok\": true, \"result\": 0.1, \"stdout\": \"\"}\n", run("call", p.toString(), "tenth").out());
        // NaN and infinities used to come back as null with "ok": true.
        for (String args : new String[] {"{\"a\": 0, \"b\": 0}", "{\"a\": 1, \"b\": 0}"}) {
            Result r = run("call", p.toString(), "ratio", args);
            assertEquals(1, r.code(), r.out());
            assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_result\", \"message\": \"result[1]: "), r.out());
        }
        // A number too large for the type rounded to an infinity.
        for (String[] call : new String[][] {{"ratio", "{\"a\": 1e400, \"b\": 1}"}, {"narrow", "{\"x\": 1e39}"}}) {
            Result r = run("call", p.toString(), call[0], call[1]);
            assertEquals(1, r.code(), r.out());
            assertTrue(r.out().contains("\"code\": \"bad_arguments\"") && r.out().contains("is out of range for f"), r.out());
        }
    }

    @Test
    void theDefaultMemoryBudgetStopsProgramsThatWouldExhaustMemory() throws IOException {
        // Each of these passed `check` and then exhausted the JVM's memory: 8 TiB in a local, in a
        // temporary, and in a constant (which `check` itself evaluates).
        String grid = "[[0; 1048576]; 1048576]";
        Path local = write("local.al", "/// Builds 8 TiB.\ntool fn big(n: i64) -> i64 {\n    let g: [[i64; 1048576]; 1048576] = "
            + grid + ";\n    return g[0][0] + n;\n}\n");
        Result r = run("call", local.toString(), "big", "{\"n\": 1}");
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("\"code\": \"E0402\""), r.out());

        Path temporary = write("temporary.al", "fn main() { print(" + grid + "[0][0]); }\n");
        r = run("run", temporary.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().startsWith("error[E0402]: fn main needs up to 8796101410816 bytes of stack, more than "
            + "the budget of 8388608 bytes"), r.err());

        Path constant = write("constant.al", "const BIG: [[i64; 1048576]; 1048576] = " + grid + ";\nfn main() { print(BIG[0][0]); }\n");
        r = run("check", constant.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().startsWith("error[E0405]: "), r.err());

        // --max-stack sets the budget; 0 turns it off (checked only: running would exhaust memory).
        assertEquals(0, run("check", temporary.toString(), "--max-stack", "0").code());
        Path small = write("small.al", "fn main() { let xs: [i64; 4] = [1, 2, 3, 4]; print(xs); }\n");
        assertEquals(0, run("check", small.toString()).code());
        assertTrue(run("check", small.toString(), "--max-stack", "32").err().startsWith("error[E0402]: "));
    }

    @Test
    void negativeLimitsAreUsageErrors() {
        // They used to mean "no limit" without saying so.
        for (String option : new String[] {"--max-steps", "--max-stack"}) {
            Result r = run("check", "missing.al", option, "-1");
            assertEquals(2, r.code(), r.err());
            assertTrue(r.err().contains(option + " must be 0 (no limit) or more"), r.err());
        }
    }

    @Test
    void capturedOutputIsCapped() throws IOException {
        // A 4000-byte line printed 1000 times, 4 MB in all: `call` keeps the first MiB and says so.
        // The step budget bounds how often a program prints, not how much, and a million lines used to
        // exhaust the JVM's memory.
        Path p = write("spam.al", """
            tool fn spam(n: i64) -> i64 {
                var line: str[4000] = "";
                for i in 0..4000 {
                    push(mut line, 'x');
                }
                for i in 0..n {
                    print(line);
                }
                return n;
            }

            test "prints, then fails" {
                let n: i64 = spam(1000);
                assert n == 0;
            }
            """);
        Result r = run("call", p.toString(), "spam", "{\"n\": 1000}");
        assertEquals(0, r.code(), r.err());
        Map<?, ?> j = (Map<?, ?>) Json.parse(r.out());
        assertEquals(true, j.get("ok"));
        assertEquals(Main.MAX_CAPTURED_OUTPUT, ((String) j.get("stdout")).length());
        assertEquals(true, j.get("stdout_truncated"));
        assertEquals(BigInteger.valueOf(4_001_000), j.get("stdout_bytes"));

        r = run("call", p.toString(), "spam", "{\"n\": 2}");
        assertFalse(r.out().contains("stdout_truncated"), r.out());

        r = run("test", p.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("    output truncated: the program printed 4001000 bytes, only the first 1048576 are kept"),
            r.err().substring(Math.max(0, r.err().length() - 2000)));
    }

    @Test
    void truncatedOutputNeverEndsInHalfACharacter() {
        // "a", "é" and "€" are 1, 2 and 3 bytes long; a cap that cuts a character drops all of it.
        byte[] text = "a\u00e9\u20ac".getBytes(StandardCharsets.UTF_8);
        String[] expected = {"", "a", "a", "a\u00e9", "a\u00e9", "a\u00e9", "a\u00e9\u20ac"};
        for (int cap = 0; cap <= text.length; cap++) {
            CappedOutput o = new CappedOutput(cap);
            o.write(text, 0, 1);
            o.write(text, 1, text.length - 1);
            assertEquals(expected[cap], o.text(), "cap " + cap);
            assertEquals(cap < text.length, o.truncated(), "cap " + cap);
            assertEquals(text.length, o.total());
        }
    }

    @Test
    void longPrintsAreWrittenWhole() throws IOException {
        // print() streams through an 8 KB buffer instead of building each line in memory first.
        Path p = write("long.al", """
            fn main() {
                var xs: list[i64; 3000] = [];
                for i in 0..3000 {
                    push(mut xs, i * 1000003);
                }
                print("xs = ", xs, ".");
                print("next");
            }
            """);
        StringBuilder expected = new StringBuilder("xs = [");
        for (long i = 0; i < 3000; i++) expected.append(i == 0 ? "" : ", ").append(i * 1000003);
        expected.append("].\nnext\n");
        Result r = run("run", p.toString());
        assertEquals(0, r.code(), r.err());
        assertEquals(expected.toString(), r.out());
    }
}
