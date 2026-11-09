package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line: the JSON reports of `check`, tool calls with malformed JSON, the nesting limit,
 * and non-ASCII arguments.
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
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "exec \"$ALANG_JAVA\" -cp \"$ALANG_CP\" alang.cli.Main " + shellArgs)
            .directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("LC_") || k.equals("LANG") || k.equals("JAVA_TOOL_OPTIONS"));
        env.put("LC_ALL", locale);
        env.put("ALANG_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        env.put("ALANG_CP", System.getProperty("java.class.path"));
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the JVM did not exit");
        return new Result(p.exitValue(), out, err);
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

    /** `check --json` for a program that compiles: no diagnostics, and the memory report of every routine. */
    @Test
    void checkJsonReportsMemory() throws IOException {
        Path p = write("ok.al", """
            const LIMIT: i64 = 3;

            fn twice(x: i64) -> i64 {
                return x * 2;
            }

            /// Doubles a number.
            tool fn double(x: i64) -> i64 {
                return twice(x);
            }

            fn main() {
                print(twice(LIMIT));
            }

            test "doubles" {
                assert twice(2) == 4;
            }
            """);
        Result r = run("check", p.toString(), "--json");
        assertEquals(0, r.code(), r.err());
        assertEquals("""
            {
              "ok": true,
              "file": "FILE",
              "errors": 0,
              "warnings": 0,
              "diagnostics": [],
              "memory": {
                "heap_bytes": 0,
                "static_bytes": 8,
                "max_stack_bytes": 32,
                "routines": [
                  {
                    "name": "fn twice",
                    "frame_bytes": 16,
                    "stack_bytes": 16,
                    "entry_point": false
                  },
                  {
                    "name": "fn double",
                    "frame_bytes": 16,
                    "stack_bytes": 32,
                    "entry_point": true
                  },
                  {
                    "name": "fn main",
                    "frame_bytes": 0,
                    "stack_bytes": 16,
                    "entry_point": true
                  },
                  {
                    "name": "test \\"doubles\\"",
                    "frame_bytes": 0,
                    "stack_bytes": 16,
                    "entry_point": true
                  }
                ]
              }
            }
            """, r.out().replace(p.toString(), "FILE"));
        assertEquals("", r.err());
    }

    /** `check --json` for a program that does not compile: errors with and without a hint, a warning, no memory report. */
    @Test
    void checkJsonReportsDiagnostics() throws IOException {
        Path p = write("bad.al", "fn main() {\n\tlet s: str[4] = \"abcde\";\n    print(nope);\n    return;\n    print(1);\n}\n");
        Result r = run("check", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        assertEquals("""
            {
              "ok": false,
              "file": "FILE",
              "errors": 2,
              "warnings": 1,
              "diagnostics": [
                {
                  "severity": "error",
                  "code": "E0201",
                  "title": "type mismatch",
                  "message": "type mismatch: expected str[4], found str[5]",
                  "file": "FILE",
                  "line": 2,
                  "column": 18,
                  "length": 7,
                  "source_line": "\\tlet s: str[4] = \\"abcde\\";",
                  "hint": "a str[5] may not fit in str[4]; increase the capacity of the target"
                },
                {
                  "severity": "error",
                  "code": "E0101",
                  "title": "unknown name",
                  "message": "unknown name `nope`",
                  "file": "FILE",
                  "line": 3,
                  "column": 11,
                  "length": 4,
                  "source_line": "    print(nope);",
                  "hint": null
                },
                {
                  "severity": "warning",
                  "code": "W0301",
                  "title": "unreachable code",
                  "message": "unreachable statement",
                  "file": "FILE",
                  "line": 5,
                  "column": 5,
                  "length": 9,
                  "source_line": "    print(1);",
                  "hint": "remove it, or move it before the return/break"
                }
              ],
              "memory": null
            }
            """, r.out().replace(p.toString(), "FILE"));
        assertEquals("", r.err());
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
    void integerArgumentsWithHugeExponentsAreRejectedQuickly() throws IOException {
        // Converting these exactly used to run for minutes.
        Path p = write("t.al", "/// Identity.\ntool fn id(x: i64) -> i64 { return x; }\n");
        Result big = run("call", p.toString(), "id", "{\"x\": 1e100000000}");
        assertEquals(1, big.code(), big.err());
        assertTrue(big.out().contains("\"message\": \"x: 1E+100000000 is out of range for i64"), big.out());
        Result tiny = run("call", p.toString(), "id", "{\"x\": 1e-100000000}");
        assertEquals(1, tiny.code(), tiny.err());
        assertTrue(tiny.out().contains("\"message\": \"x: expected an integer, found 1E-100000000\""), tiny.out());
        assertEquals("{\"ok\": true, \"result\": 0, \"stdout\": \"\"}\n", run("call", p.toString(), "id", "{\"x\": 0e-100000000}").out());
        assertEquals("{\"ok\": true, \"result\": 1, \"stdout\": \"\"}\n", run("call", p.toString(), "id", "{\"x\": 100e-2}").out());
        assertEquals("{\"ok\": true, \"result\": -9223372036854775808, \"stdout\": \"\"}\n",
            run("call", p.toString(), "id", "{\"x\": -9.223372036854775808e18}").out());
    }

    @Test
    void f32ArgumentsAreRoundedOnce() throws IOException {
        // Just above halfway between 1 and the next f32: via f64 it would land on the halfway point and round down.
        Path p = write("t.al", "/// Identity.\ntool fn id(x: f32) -> bool { return x != 1.0; }\n");
        assertEquals("{\"ok\": true, \"result\": true, \"stdout\": \"\"}\n",
            run("call", p.toString(), "id", "{\"x\": 1.00000005960464477539062500000001}").out());
    }

    @Test
    void negativeSizesAreReportedAsNegative() throws IOException {
        Result r = run("check", write("t.al", "fn main() { let s: str[-1] = \"\"; }\n").toString());
        assertTrue(r.err().startsWith("error[E0210]: string capacity must be between 1 and 1048576, found -1\n"), r.err());
    }

    @Test
    void deeplyNestedJsonArgumentsAreAJsonError() throws IOException {
        Path p = write("t.al", "/// Identity.\ntool fn id(x: i64) -> i64 { return x; }\n");
        Result r = run("call", p.toString(), "id", "{\"x\": " + "[".repeat(5000) + "]".repeat(5000) + "}");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_json\""), r.out());
    }
}
