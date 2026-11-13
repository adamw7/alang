package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.Harness;
import alang.util.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line: the JSON reports of `check`, tool calls with malformed JSON, the nesting limit,
 * non-ASCII arguments, the memory budget, captured output, arguments on stdin and the version.
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

    /**
     * A report as the CLI prints it, from the same JSON written readably: one line, a space after each
     * comma and colon, the keys in the same order.
     */
    private static String oneLine(String json) {
        return Json.write(Json.parse(json)) + "\n";
    }

    /**
     * Why the tests of argument bytes do not run on Windows: there a program gets its arguments as UTF-16
     * text, which the POSIX locale does not change and which cannot hold bytes that are not UTF-8, and
     * the tests build the bytes with a POSIX shell.
     */
    private static final String POSIX_ONLY = "arguments are UTF-16 text on Windows, not bytes decoded in a POSIX locale";

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

    /** Runs the command line in a new JVM, with arguments that a POSIX shell builds from {@code shellArgs}. */
    private Result runInLocale(String locale, String shellArgs) throws Exception {
        return start(locale, new ProcessBuilder("sh", "-c", "exec \"$ALANG_JAVA\" -cp \"$ALANG_CP\" alang.cli.Main " + shellArgs),
            new byte[0], false);
    }

    /**
     * Runs the command line in a new JVM with {@code stdin} as its input. With {@code keepStdinOpen},
     * stdin never ends, like that of an agent harness that does not close it: a command that reads it
     * never exits, and the test fails on its time limit.
     */
    private Result runProcess(String locale, byte[] stdin, boolean keepStdinOpen, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"), "alang.cli.Main"));
        command.addAll(List.of(args));
        return start(locale, new ProcessBuilder(command), stdin, keepStdinOpen);
    }

    /** The java launcher of this JVM. */
    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private Result start(String locale, ProcessBuilder pb, byte[] stdin, boolean keepStdinOpen) throws Exception {
        pb.directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("LC_") || k.equals("LANG") || k.equals("JAVA_TOOL_OPTIONS"));
        env.put("LC_ALL", locale);
        env.put("ALANG_JAVA", java());
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
    void versionIsTheOneInPomXml() throws IOException {
        // A release publishes the jar under the pom's version, from a tag that must match it
        // (.github/workflows/release.yml); `alang version` and the MCP server report Main.VERSION.
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<artifactId>alang</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find(), "pom.xml: the project's <version> was not found");
        assertEquals(m.group(1), Main.VERSION, "Main.VERSION must be the <version> in pom.xml; change both");
        assertEquals(new Result(0, "alang " + m.group(1) + "\n", ""), run("version"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = POSIX_ONLY)
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
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = POSIX_ONLY)
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
        assertEquals(oneLine("""
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
            """), r.out().replace(Json.write(p.toString()), "\"FILE\""));
        assertEquals("", r.err());
    }

    /** `check --json` for a program that does not compile: errors with and without a hint, a warning, no memory report. */
    @Test
    void checkJsonReportsDiagnostics() throws IOException {
        Path p = write("bad.al", "fn main() {\n\tlet s: str[4] = \"abcde\";\n    print(nope);\n    return;\n    print(1);\n}\n");
        Result r = run("check", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        assertEquals(oneLine("""
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
                  "line": 5,
                  "column": 5,
                  "length": 9,
                  "source_line": "    print(1);",
                  "hint": "remove it, or move it before the return/break"
                }
              ],
              "memory": null
            }
            """), r.out().replace(Json.write(p.toString()), "\"FILE\""));
        assertEquals("", r.err());
    }

    /**
     * Every JSON report is one line. `run`, `test` and `tools` name the file once at the top level, as
     * `check` does: a diagnostic or a trap does not repeat it. `call`, whose caller passed the file, does
     * not name it at all.
     */
    @Test
    void reportsNameTheFileOnce() throws IOException {
        Path p = write("trap.al", """
            /// The item at an index.
            tool fn at(i: i64) -> i64 {
                let xs: [i64; 2] = [1, 2];
                return xs[i];
            }

            fn main() {
                print(at(5));
            }

            test "past the end" {
                assert at(5) == 0;
            }
            """);
        Result r = run("run", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        Map<?, ?> j = reportOn(p, r, 1);
        assertEquals("R0003", ((Map<?, ?>) j.get("trap")).get("code"), r.out());
        assertEquals(BigInteger.valueOf(4), ((Map<?, ?>) j.get("trap")).get("line"), r.out());

        r = run("test", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        j = reportOn(p, r, 1);
        Map<?, ?> test = (Map<?, ?>) ((List<?>) j.get("tests")).get(0);
        assertEquals("R0003", ((Map<?, ?>) test.get("trap")).get("code"), r.out());

        r = run("call", p.toString(), "at", "{\"i\": 5}");
        assertEquals(1, r.code(), r.err());
        j = reportOn(p, r, 0);
        assertEquals("R0003", ((Map<?, ?>) j.get("error")).get("code"), r.out());

        Path bad = write("bad.al", "tool fn f() -> i64 {\n    return true;\n}\n\nfn main() {\n}\n");
        for (String cmd : List.of("check", "run", "test", "tools")) {
            r = run(cmd, bad.toString(), "--json");
            assertEquals(1, r.code(), cmd + ": " + r.err());
            j = reportOn(bad, r, 1);
            Map<?, ?> diag = (Map<?, ?>) ((List<?>) j.get("diagnostics")).get(0);
            assertEquals("E0201", diag.get("code"), r.out());
            assertEquals(BigInteger.valueOf(2), diag.get("line"), r.out());
        }
        r = run("call", bad.toString(), "f");
        assertEquals(1, r.code(), r.err());
        j = reportOn(bad, r, 0);
        assertEquals(1, ((List<?>) j.get("diagnostics")).size(), r.out());
    }

    /** The report in {@code r.out()}: one line, naming the file {@code p} at the top level {@code times} times (0 or 1) and nowhere else. */
    private static Map<?, ?> reportOn(Path p, Result r, int times) {
        assertTrue(r.out().endsWith("\n") && r.out().indexOf('\n') == r.out().length() - 1, r.out());
        assertEquals(times, r.out().split("\"file\": ", -1).length - 1, r.out());
        assertEquals(times, r.out().split(Pattern.quote(Json.write(p.toString())), -1).length - 1, r.out());
        Map<?, ?> j = (Map<?, ?>) Json.parse(r.out());
        assertEquals(times == 1 ? p.toString() : null, j.get("file"), r.out());
        assertEquals("", r.err());
        return j;
    }

    private static Result runWithStdin(InputStream stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
            stdin).run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * A main that takes a str gets all of the standard input, as bytes; input past its capacity traps before
     * main runs. A main without a parameter never reads stdin, so it cannot wait on a caller that leaves it open.
     */
    @Test
    void mainReadsTheStandardInput() throws IOException {
        Path p = write("count.al", """
            fn main(input: str[8]) {
                let lines: list[str[8]; 9] = split(trim(input), '\\n');
                print(len(input), " bytes, ", len(lines), " lines");
            }
            """);
        Result r = runWithStdin(new ByteArrayInputStream("a\nbc\n".getBytes(StandardCharsets.UTF_8)), "run", p.toString());
        assertEquals(new Result(0, "5 bytes, 2 lines\n", ""), r);

        r = runWithStdin(new ByteArrayInputStream("123456789".getBytes(StandardCharsets.UTF_8)), "run", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        Map<?, ?> trap = (Map<?, ?>) ((Map<?, ?>) Json.parse(r.out())).get("trap");
        assertEquals("R0004", trap.get("code"), r.out());
        assertEquals("standard input exceeds str capacity 8 of `input`", trap.get("message"), r.out());
        assertEquals(List.of(BigInteger.ONE, BigInteger.valueOf(9)), List.of(trap.get("line"), trap.get("column")), r.out());

        Path hello = write("hello.al", "fn main() {\n    print(\"hi\");\n}\n");
        InputStream unreadable = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("stdin was read");
            }
        };
        assertEquals(new Result(0, "hi\n", ""), runWithStdin(unreadable, "run", hello.toString()));
    }

    /**
     * A diagnostic or trap in an imported file names that file, also in JSON, where the report names the
     * main file once; a call there names it in the trace. The imported file's main, tests and tools are
     * its own: the program has the main, tests and tools of the file given on the command line.
     */
    @Test
    void importedFilesAreNamedAndKeepTheirMainTestsAndTools() throws IOException {
        Files.createDirectories(dir.resolve("lib"));
        Path lib = write("lib/util.al", """
            fn item(i: i64) -> i64 {
                let xs: [i64; 2] = [1, 2];
                return xs[i];
            }

            /// A tool of this file, and a plain function where it is imported.
            tool fn at(i: i64) -> i64 {
                return item(i);
            }

            fn main() {
                print("not this main");
            }

            test "not this test" {
                assert false;
            }
            """);
        Path p = write("prog.al", """
            import "lib/util.al";

            /// Doubles the item at an index.
            tool fn double_at(i: i64) -> i64 {
                return 2 * at(i);
            }

            fn main() {
                print(double_at(5));
            }

            test "in range" {
                assert double_at(1) == 4;
            }
            """);
        String libPath = Frontend.resolve(p.toString(), "lib/util.al");
        assertEquals(lib.toAbsolutePath().normalize(), Path.of(libPath).toAbsolutePath().normalize());

        Result r = run("run", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        Map<?, ?> j = (Map<?, ?>) Json.parse(r.out());
        assertEquals(p.toString(), j.get("file"), r.out());
        Map<?, ?> trap = (Map<?, ?>) j.get("trap");
        assertEquals(List.of("R0003", libPath, BigInteger.valueOf(3), "    return xs[i];"),
            List.of(trap.get("code"), trap.get("file"), trap.get("line"), trap.get("source_line")), r.out());
        assertEquals(List.of("fn item (called at " + libPath + ":8:12)", "fn at (called at 5:16)",
            "fn double_at (called at 9:11)", "fn main (entry point, line 8)"), trap.get("trace"), r.out());

        r = run("run", p.toString());
        assertTrue(r.err().contains(" --> " + libPath + ":3:12\n"), r.err());

        r = run("tools", p.toString());
        assertEquals(List.of("double_at"), ((List<?>) Json.parse(r.out())).stream().map(t -> ((Map<?, ?>) t).get("name")).toList(), r.out());

        r = run("test", p.toString());
        assertEquals(new Result(0, "test \"in range\" ... ok\n\n1 test(s): 1 passed, 0 failed\n", ""), r);

        write("lib/util.al", Files.readString(lib, StandardCharsets.UTF_8).replace("return item(i);", "return item(true);"));
        r = run("check", p.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        j = (Map<?, ?>) Json.parse(r.out());
        Map<?, ?> diag = (Map<?, ?>) ((List<?>) j.get("diagnostics")).get(0);
        assertEquals(List.of("E0201", libPath, BigInteger.valueOf(8)), List.of(diag.get("code"), diag.get("file"), diag.get("line")), r.out());
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
    void negativeZeroKeepsItsSignInToolCalls() throws IOException {
        // BigDecimal and BigInteger have no negative zero, so a result of -0.0 came back as 0.0 and an
        // argument of -0.0 arrived as 0.0, although print tells them apart.
        Path p = write("t.al", "/// Negates.\ntool fn neg(d: f64) -> f64 { print(d); return -d; }\n"
            + "/// Negates.\ntool fn neg32(d: f32) -> f32 { return -d; }\n"
            + "/// Identity.\ntool fn id(x: i64) -> i64 { return x; }\n");
        assertEquals("{\"ok\": true, \"result\": -0.0, \"stdout\": \"0.0\\n\", \"steps\": 2}\n", run("call", p.toString(), "neg", "{\"d\": 0}").out());
        assertEquals("{\"ok\": true, \"result\": 0.0, \"stdout\": \"-0.0\\n\", \"steps\": 2}\n", run("call", p.toString(), "neg", "{\"d\": -0.0}").out());
        assertEquals("{\"ok\": true, \"result\": 0.0, \"stdout\": \"-0.0\\n\", \"steps\": 2}\n", run("call", p.toString(), "neg", "{\"d\": -0e5}").out());
        assertEquals("{\"ok\": true, \"result\": -0.0, \"stdout\": \"\", \"steps\": 1}\n", run("call", p.toString(), "neg32", "{\"d\": 0.0}").out());
        // Written as an integer, -0 is the integer 0 (as jsonIsReadStrictly expects), and an integer
        // parameter takes -0.0 as 0.
        assertEquals("{\"ok\": true, \"result\": -0.0, \"stdout\": \"0.0\\n\", \"steps\": 2}\n", run("call", p.toString(), "neg", "{\"d\": -0}").out());
        assertEquals("{\"ok\": true, \"result\": 0, \"stdout\": \"\", \"steps\": 1}\n", run("call", p.toString(), "id", "{\"x\": -0.0}").out());
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
        assertEquals("{\"ok\": true, \"result\": 0, \"stdout\": \"\", \"steps\": 1}\n", run("call", p.toString(), "id", "{\"x\": 0e-100000000}").out());
        assertEquals("{\"ok\": true, \"result\": 1, \"stdout\": \"\", \"steps\": 1}\n", run("call", p.toString(), "id", "{\"x\": 100e-2}").out());
        assertEquals("{\"ok\": true, \"result\": -9223372036854775808, \"stdout\": \"\", \"steps\": 1}\n",
            run("call", p.toString(), "id", "{\"x\": -9.223372036854775808e18}").out());
    }

    @Test
    void f32ArgumentsAreRoundedOnce() throws IOException {
        // Just above halfway between 1 and the next f32: via f64 it would land on the halfway point and round down.
        Path p = write("t.al", "/// Identity.\ntool fn id(x: f32) -> bool { return x != 1.0; }\n");
        assertEquals("{\"ok\": true, \"result\": true, \"stdout\": \"\", \"steps\": 1}\n",
            run("call", p.toString(), "id", "{\"x\": 1.00000005960464477539062500000001}").out());
    }

    /** `steps` is the cost of a call that ran, trap or not; a call refused before it ran has none. */
    @Test
    void callsReportTheStepsTheyRan() throws IOException {
        Path p = write("t.al", """
            tool fn sum(n: i64) -> i64 {
                var total: i64 = 0;
                for i in 0..n {
                    total += 10 / (3 - i);
                }
                return total;
            }
            """);
        // var, for, two iterations of one step each plus the +=, return: 7.
        assertEquals("{\"ok\": true, \"result\": 8, \"stdout\": \"\", \"steps\": 7}\n",
            run("call", p.toString(), "sum", "{\"n\": 2}").out());
        // The fourth iteration divides by zero at its += , the tenth step.
        Map<?, ?> trap = (Map<?, ?>) Json.parse(run("call", p.toString(), "sum", "{\"n\": 5}").out());
        assertEquals("R0002", ((Map<?, ?>) trap.get("error")).get("code"), trap.toString());
        assertEquals(BigInteger.valueOf(10), trap.get("steps"), trap.toString());
        assertEquals("{\"ok\": false, \"error\": {\"code\": \"bad_arguments\", \"message\": \"n: expected an integer\"}}\n",
            run("call", p.toString(), "sum", "{\"n\": \"2\"}").out());
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

    @Test
    void callWithoutArgumentsDoesNotReadStdin() throws Exception {
        // `call` used to read its arguments from stdin whenever argv had none, so a call to a tool
        // without parameters waited forever on a caller that left stdin open.
        write("one.al", "tool fn one() -> i64 {\n    return 1;\n}\n");
        Result r = runProcess("C.UTF-8", new byte[0], true, "call", "one.al", "one");
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("\"result\": 1"), r.out());
    }

    /**
     * `serve` answers on stdout and writes nothing else there: a warning goes to stderr, and a program
     * with errors starts no server. It stops when stdin ends.
     */
    @Test
    void serveKeepsStdoutForTheProtocol() throws Exception {
        write("one.al", "fn two() -> i64 {\n    return 2;\n}\n\ntool fn one() -> i64 {\n    two();\n    return 1;\n}\n");
        String session = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {}}\n"
            + "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}\n"
            + "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/call\", \"params\": {\"name\": \"one\"}}\n";
        Result r = runProcess("C.UTF-8", session.getBytes(StandardCharsets.UTF_8), false, "serve", "one.al");
        assertEquals(0, r.code(), r.out() + r.err());
        String[] lines = r.out().split("\n");
        assertEquals(2, lines.length, r.out());
        assertTrue(lines[0].startsWith("{\"jsonrpc\": \"2.0\", \"id\": 1, \"result\": {\"protocolVersion\": "), lines[0]);
        assertEquals("{\"jsonrpc\": \"2.0\", \"id\": 2, \"result\": {\"content\": [{\"type\": \"text\", \"text\": "
            + "\"{\\\"ok\\\": true, \\\"result\\\": 1, \\\"stdout\\\": \\\"\\\", \\\"steps\\\": 3}\"}], "
            + "\"structuredContent\": {\"result\": 1}, \"isError\": false}}", lines[1]);
        assertTrue(r.err().startsWith("warning[W0302]: "), r.err());

        write("bad.al", "tool fn bad() -> i64 {\n    return true;\n}\n");
        r = runProcess("C.UTF-8", session.getBytes(StandardCharsets.UTF_8), false, "serve", "bad.al");
        assertEquals(1, r.code(), r.err());
        assertEquals("", r.out());
        assertTrue(r.err().startsWith("error[E0201]: ") && r.err().endsWith("check failed: 1 error(s)\n"), r.err());
    }

    @Test
    void dashReadsTheArgumentsFromStdinAsStrictUtf8() throws Exception {
        write("size.al", "tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n");
        Result r = runProcess("C", "{\"text\": \"\u00e9\"}".getBytes(StandardCharsets.UTF_8), false, "call", "size.al", "size", "-");
        assertEquals(0, r.code(), r.out() + r.err());
        assertTrue(r.out().contains("\"result\": 2"), r.out());
        // An invalid byte used to become U+FFFD, 3 bytes long, and the tool ran on text nobody sent.
        r = runProcess("C", new byte[] {'{', '"', 't', 'e', 'x', 't', '"', ':', '"', (byte) 0xFF, '"', '}'}, false, "call", "size.al", "size", "-");
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
        assertEquals("{\"ok\": true, \"result\": 0.1, \"stdout\": \"\", \"steps\": 1}\n", run("call", p.toString(), "tenth").out());
        // Every float result is written as a float, never as an integer: 123456789.0 used to come back as
        // 123456789 (Python's json reads an int) while 1234567.0 came back as 1234567.0.
        assertEquals("{\"ok\": true, \"result\": [1.0, 123456789.0], \"stdout\": \"\", \"steps\": 1}\n",
            run("call", p.toString(), "ratio", "{\"a\": 123456789, \"b\": 1}").out());
        assertEquals("{\"ok\": true, \"result\": [1.0, 1234567.0], \"stdout\": \"\", \"steps\": 1}\n",
            run("call", p.toString(), "ratio", "{\"a\": 1234567, \"b\": 1}").out());
        assertEquals("{\"ok\": true, \"result\": 16777216.0, \"stdout\": \"\", \"steps\": 1}\n",
            run("call", p.toString(), "narrow", "{\"x\": 16777216}").out());
        assertEquals("{\"ok\": true, \"result\": 1.0E+16, \"stdout\": \"\", \"steps\": 1}\n", run("call", p.toString(), "narrow", "{\"x\": 1e16}").out());
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
    void strResultsMustBeUtf8() throws IOException {
        // Bytes that are not UTF-8 used to come back as U+FFFD with "ok": true, so the bytes 0xff 0xc0 and
        // the three bytes of U+FFFD itself gave the same result.
        Path p = write("b.al", """
            struct Named {
                name: str[8],
            }

            tool fn named(xs: list[u8; 8]) -> Named {
                var s: str[8] = "";
                for x in xs {
                    push(mut s, x);
                }
                return Named(name: s);
            }
            """);
        Result bad = run("call", p.toString(), "named", "{\"xs\": [104, 105, 255, 192]}");
        assertEquals(1, bad.code(), bad.out());
        assertTrue(bad.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"bad_result\", \"message\": "
            + "\"result.name: the str value is not UTF-8 text (byte 0xff at offset 2)"), bad.out());
        Result good = run("call", p.toString(), "named", "{\"xs\": [104, 105, 239, 191, 189]}");
        assertEquals("{\"ok\": true, \"result\": {\"name\": \"hi\uFFFD\"}, \"stdout\": \"\", \"steps\": 13}\n", good.out());
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
            Result r = run("run", "missing.al", option, "-1");
            assertEquals(2, r.code(), r.err());
            assertTrue(r.err().contains(option + " must be 0 (no limit) or more"), r.err());
        }
    }

    @Test
    void explainTakesTheFormsItsUsageShows() {
        // `explain --all` and `explain --markdown` used to fail with "unknown option".
        for (String form : new String[] {"--all", "all"}) {
            Result r = run("explain", form);
            assertEquals(0, r.code(), r.err());
            assertTrue(r.out().startsWith("E0001: syntax error\n"), r.out());
        }
        for (String form : new String[] {"--markdown", "markdown"}) {
            Result r = run("explain", form);
            assertEquals(0, r.code(), r.err());
            assertEquals(Main.errorsMarkdown(), r.out());
        }
    }

    @Test
    void argumentsACommandDoesNotTakeAreUsageErrors() throws IOException {
        // They used to be ignored: `check -o x.c` wrote no file, and arguments after the file were dropped.
        String hello = write("hello.al", "fn main() {\n    print(1);\n}\n").toString();
        String[][] cases = {
            {"unexpected argument `extra` for `alang run`", "run", hello, "extra"},
            {"unexpected argument `E0201` for `alang explain`", "explain", "E0101", "E0201"},
            {"unexpected argument `now` for `alang version`", "version", "now"},
            {"-o does not apply to `alang check`", "check", hello, "-o", "x.c"},
            {"--filter does not apply to `alang run`", "run", hello, "--filter", "x"},
            {"--max-steps does not apply to `alang check`", "check", hello, "--max-steps", "5"},
            {"--json does not apply to `alang build`", "build", hello, "--json"},
            {"unknown option --verbose", "run", hello, "--verbose"},
            {"unknown option -v", "run", hello, "-v"},
            {"unknown command `bogus`", "bogus", "--json"},
        };
        for (String[] c : cases) {
            Result r = run(Arrays.copyOfRange(c, 1, c.length));
            assertEquals(2, r.code(), String.join(" ", c));
            assertTrue(r.err().startsWith("alang: " + c[0]), r.err());
            assertEquals("", r.out());
        }
        assertFalse(Files.exists(dir.resolve("x.c")));
        // `call` takes a file, a tool and its arguments, and reports the extra argument as JSON.
        Path tool = write("one.al", "tool fn one() -> i64 {\n    return 1;\n}\n");
        Result r = run("call", tool.toString(), "one", "{}", "extra");
        assertEquals(2, r.code(), r.out());
        assertEquals("{\"ok\": false, \"error\": {\"code\": \"usage_error\", \"message\": \"unexpected argument `extra` for "
            + "`alang call`; see `alang help`\"}}\n", r.out());
    }

    @Test
    void usageErrorsAreJsonWhenTheOutputIs() throws IOException {
        // A missing file used to be reported as text on stderr even with --json, and by `call`.
        Result r = run("check", "missing.al", "--json");
        assertEquals(2, r.code(), r.err());
        assertEquals("{\"ok\": false, \"error\": \"file not found: missing.al\"}\n", r.out());
        assertEquals("", r.err());
        // An error found before --json is reached.
        r = run("run", "missing.al", "--max-steps", "many", "--json");
        assertEquals(2, r.code(), r.err());
        assertEquals("{\"ok\": false, \"error\": \"expected a number: For input string: \\\"many\\\"\"}\n", r.out());
        // `--filter --json` filters on the text "--json"; it does not ask for JSON.
        r = run("test", "missing.al", "--filter", "--json");
        assertEquals(2, r.code());
        assertEquals("alang: file not found: missing.al\n", r.err());
        r = run("call", "missing.al", "f");
        assertEquals(2, r.code(), r.err());
        assertEquals("{\"ok\": false, \"error\": {\"code\": \"usage_error\", \"message\": \"file not found: missing.al\"}}\n", r.out());
        assertEquals("", r.err());
    }

    @Test
    void theCallsShownInTheExamplesReadmeAreCurrent() {
        // Each `bin/alang call` line there is followed by its output as a `# ` comment. Nothing checked them, so
        // the step count of fit_line stayed 49 after a fix made it 68.
        String doc = Harness.read(Harness.ROOT.resolve("examples/README.md"));
        Matcher m = Pattern.compile("^bin/alang call (\\S+) (\\S+) '([^']*)'\\n# (.*)$", Pattern.MULTILINE).matcher(doc);
        int calls = 0;
        while (m.find()) {
            Result r = run("call", Harness.ROOT.resolve(m.group(1)).toString(), m.group(2), m.group(3));
            assertEquals(m.group(4) + "\n", r.out(), "examples/README.md: the output shown for " + m.group(1) + " " + m.group(2));
            calls++;
        }
        assertTrue(calls >= 5, "expected the `bin/alang call` lines of examples/README.md");
    }

    @Test
    void sourceFilesThatAreNotUtf8SayWhere() throws IOException {
        // Files.readString only said "cannot read bad.al: Input length = 1".
        Path p = dir.resolve("bad.al");
        Files.write(p, new byte[] {'f', 'n', ' ', 'm', (byte) 0xE9, '(', ')', ' ', '{', '}', '\n'});
        Result r = run("check", p.toString());
        assertEquals(2, r.code(), r.err());
        assertEquals("alang: " + p + " is not UTF-8 text (byte 0xe9 at offset 4); save it as UTF-8\n", r.err());
        // A sequence cut off by the end of the file.
        Files.write(p, new byte[] {'/', '/', ' ', (byte) 0xC3});
        r = run("check", p.toString(), "--json");
        assertEquals(2, r.code(), r.err());
        assertEquals(Json.write(Json.obj("ok", false, "error", p + " is not UTF-8 text (byte 0xc3 at offset 3); save it as UTF-8"))
            + "\n", r.out());
    }

    @Test
    void compileErrorsInToolCommandsAreReportedAsJson() throws IOException {
        Path bad = write("bad.al", "tool fn f() -> i64 {\n    return true;\n}\n");
        // `call` used to report them with the code "E".
        Result r = run("call", bad.toString(), "f");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.out().startsWith("{\"ok\": false, \"error\": {\"code\": \"compile_error\", "), r.out());
        r = run("tools", bad.toString(), "--json");
        assertEquals(1, r.code(), r.err());
        Map<?, ?> j = (Map<?, ?>) Json.parse(r.out());
        assertEquals(false, j.get("ok"));
        assertEquals(1, ((List<?>) j.get("diagnostics")).size(), r.out());
        assertEquals("", r.err());
        r = run("tools", bad.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().startsWith("error[E0201]: "), r.err());
    }

    @Test
    void aFilterThatMatchesNoTestFails() throws IOException {
        // It used to run no test and exit 0, so a typo in the filter passed.
        Path p = write("t.al", "test \"adds\" {\n    assert 1 + 1 == 2;\n}\n\ntest \"subtracts\" {\n    assert 2 - 1 == 1;\n}\n");
        Result r = run("test", p.toString(), "--filter", "add");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().endsWith("1 test(s): 1 passed, 0 failed\n"), r.out());
        r = run("test", p.toString(), "--filter", "ads");
        assertEquals(1, r.code(), r.out());
        assertEquals("alang: no test name contains \"ads\"; the tests are \"adds\", \"subtracts\"\n", r.err());
        r = run("test", p.toString(), "--filter", "ads", "--json");
        assertEquals(1, r.code(), r.err());
        Map<?, ?> j = (Map<?, ?>) Json.parse(r.out());
        assertEquals(false, j.get("ok"));
        assertEquals("no test name contains \"ads\"; the tests are \"adds\", \"subtracts\"", j.get("error"));
        assertEquals(List.of(), j.get("tests"));
        Path none = write("none.al", "fn main() {\n    print(1);\n}\n");
        r = run("test", none.toString(), "--filter", "x");
        assertEquals(1, r.code(), r.out());
        assertEquals("alang: no test name contains \"x\"; " + none + " has no tests\n", r.err());
        // Without a filter, a file without tests still passes.
        assertEquals(0, run("test", none.toString()).code());
    }

    @Test
    void aTestThatExpectsATrapFailsWithoutItOrWithAnother() throws IOException {
        Path p = write("t.al", """
            fn half(x: i64) -> i64
                requires x % 2 == 0
            {
                return x / 2;
            }

            test "odd" traps R0006 {
                half(3);
            }

            test "even" traps R0006 {
                half(4);
            }

            test "full" traps R0006 {
                var xs: list[i64; 1] = [1];
                push(mut xs, 2);
            }
            """);
        Result r = run("test", p.toString());
        assertEquals(1, r.code(), r.err());
        assertEquals("test \"odd\" ... ok\ntest \"even\" ... FAILED\ntest \"full\" ... FAILED\n\n3 test(s): 1 passed, 2 failed\n", r.out());
        // No warning that the result of half(3) is unused, or that xs is changed but never read: they are there to trap.
        assertFalse(r.err().contains("warning"), r.err());
        assertTrue(r.err().contains("  trap[R0005]: expected trap R0006 (precondition failed), but the test ran to its end "
            + "without a trap\n    --> " + p + ":11:19\n"), r.err());
        assertTrue(r.err().contains("  trap[R0005]: expected trap R0006 (precondition failed), but got R0004 (capacity "
            + "exceeded): push to a full list (capacity 1)\n    --> " + p + ":17:5\n"), r.err());
        r = run("test", p.toString(), "--json");
        Map<?, ?> j = (Map<?, ?>) Json.parse(r.out());
        List<?> tests = (List<?>) j.get("tests");
        assertEquals(3, tests.size());
        Map<?, ?> odd = (Map<?, ?>) tests.get(0);
        assertEquals(true, odd.get("ok"));
        assertEquals("R0006", odd.get("traps"));
        assertEquals(null, odd.get("trap"));
        Map<?, ?> even = (Map<?, ?>) ((Map<?, ?>) tests.get(1)).get("trap");
        assertEquals("R0005", even.get("code"));
        assertEquals(java.math.BigInteger.valueOf(11), even.get("line"));
    }

    @Test
    void failingTestOutputKeepsBlankLines() throws IOException {
        // Blank lines used to be dropped from the output shown for a failing test.
        Path p = write("t.al", "test \"prints\" {\n    print(\"a\\n\\nb\");\n    print(\"\\n\");\n    assert false;\n}\n");
        Result r = run("test", p.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.err().startsWith("  output:\n    a\n\n    b\n\n\n  trap[R0005]: "), r.err());
    }

    @Test
    void trapMessagesKeepTheirBytes() throws IOException {
        // The 0xff byte of the panic message used to come out as U+FFFD (ef bf bd), where the C backend
        // writes the byte itself; `alang run` and `alang test` now write it as it is.
        Path p = write("bytes.al", """
            fn main() {
                var s: str[4] = "x";
                push(mut s, 0xff);
                panic(s);
            }

            test "bytes" {
                main();
            }
            """);
        byte[] expected = "trap[R0010]: panic: x\u00ff\n".getBytes(StandardCharsets.ISO_8859_1);
        for (String cmd : List.of("run", "test")) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int code = new Main(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(new String[] {cmd, p.toString()});
            assertEquals(1, code, cmd);
            String text = new String(err.toByteArray(), StandardCharsets.ISO_8859_1);
            String want = new String(expected, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains(cmd.equals("run") ? want : "  " + want), cmd + ": " + text);
        }
    }

    @Test
    void trapsQuoteTheSourceByColumn() throws IOException {
        // Columns count code points, but the source text was cut by UTF-16 chars: after the emoji (two
        // chars, one column) the assert was quoted as " n ==  ", and the caret ignored the tab.
        Path p = write("emoji.al", "fn main() {\n\tlet s: str[8] = \"\uD83D\uDE00\"; assert len(s) == 3;\n}\n");
        Result r = run("run", p.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.err().startsWith("trap[R0005]: assertion failed: len(s) == 3 (left: 4, right: 3)\n"
            + " --> " + p + ":2:23\n  |\n2 | \tlet s: str[8] = \"\uD83D\uDE00\"; assert len(s) == 3;\n"
            + "  | \t" + " ".repeat(21) + "^".repeat(19) + "\n"), r.err());
    }

    @Test
    void theStepBudgetStopsAProgramAfterExactlyThatManyStatements() throws IOException {
        // Only the interpreter counts steps, so R0011 cannot be a tests/run case, which the C backend runs too.
        Path p = write("spin.al", """
            /// Counts up until the step budget stops it.
            tool fn spin() -> i64 {
                var i: i64 = 0;
                while true limit 1000 {
                    print(i);
                    i += 1;
                }
                return i;
            }

            fn main() {
                let n: i64 = spin();
                print(n);
            }

            test "spins" {
                assert spin() > 0;
            }
            """);
        // A while costs one step, and one more per iteration. Steps: main's let (1), spin's var (2) and while
        // (3), then iteration, print, += (4-6, 7-9) and the third iteration (10): its print is step 11.
        Result r = run("run", p.toString(), "--max-steps", "10");
        assertEquals(1, r.code(), r.err());
        assertEquals("0\n1\n", r.out());
        assertTrue(r.err().startsWith("trap[R0011]: step budget of 10 statements exhausted\n --> " + p + ":5:9\n"), r.err());
        assertEquals(r, run("run", p.toString(), "--max-steps", "10"), "the same budget stops at the same statement");

        r = run("test", p.toString(), "--max-steps", "10");
        assertEquals(1, r.code(), r.out());
        assertTrue(r.err().contains("  trap[R0011]: step budget of 10 statements exhausted\n"), r.err());

        // A tool call has no main, so its count is one step ahead: the third print is step 10, its += step 11.
        r = run("call", p.toString(), "spin", "--max-steps", "10");
        assertEquals(1, r.code(), r.out());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) ((Map<String, Object>) Json.parse(r.out())).get("error");
        assertEquals("R0011", error.get("code"));
        assertEquals("step budget of 10 statements exhausted", error.get("message"));
        assertEquals(BigInteger.valueOf(6), error.get("line"));
        assertTrue(r.out().endsWith("\"stdout\": \"0\\n1\\n2\\n\", \"steps\": 11}\n"), r.out());

        // 0 turns the budget off, so the loop runs into its own limit instead.
        r = run("run", p.toString(), "--max-steps", "0");
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().startsWith("trap[R0008]: "), r.err());
        // Steps are still counted without a budget: `run --json` used to report 0 for any program.
        Path count = write("count.al", "fn main() {\n    var t: i64 = 0;\n    for i in 0..10 {\n        t += i;\n    }\n}\n");
        String counted = run("run", count.toString(), "--json").out();
        assertTrue(counted.endsWith("\"steps\": 22}\n"), counted);
        assertEquals(counted, run("run", count.toString(), "--json", "--max-steps", "0").out());

        // Without --max-steps the documented default applies.
        Path forever = write("forever.al", "fn main() {\n    while true limit 1000000000 {\n    }\n}\n");
        r = run("run", forever.toString());
        assertEquals(1, r.code(), r.err());
        assertTrue(r.err().startsWith("trap[R0011]: step budget of 100000000 statements exhausted\n"), r.err());
    }

    @Test
    void absOfTheMinimumNamesAbs() throws IOException {
        // The trap used to blame `-`, which the program does not contain.
        Path p = write("abs.al", "fn main() {\n    let x: i64 = -9223372036854775807 - 1;\n    print(abs(x));\n}\n");
        Result r = run("run", p.toString());
        assertEquals(1, r.code(), r.out());
        assertTrue(r.err().startsWith("trap[R0001]: integer overflow: result of `abs` does not fit in i64\n"), r.err());
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
    void testOutputKeepsCarriageReturnsInTrapMessages() throws IOException {
        // The trap is indented line by line, and only '\n' ends a line: String.indent also broke lines at the
        // '\r' of a str value, so `alang test` showed a message that `alang run` does not.
        Path p = write("cr.al", """
            test "cr" {
                panic("a\\rb");
            }
            """);
        Result r = run("test", p.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("  trap[R0010]: panic: a\rb\n"), r.err());
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

    @Test
    void fuzzFindsATrapTheTestsMiss() throws IOException {
        // The test passes, but a text of more than 8 different words fills the list: the trap the tool's
        // callers would hit with input its schema accepts.
        Path p = write("count.al", """
            struct Count {
                word: str[16],
                n: i64,
            }

            /// Counts each word.
            tool fn counts(text: str[256]) -> list[Count; 8] {
                var out: list[Count; 8] = [];
                for w in words(text) {
                    if len(w) > 16 {
                        continue;
                    }
                    var found: bool = false;
                    for i in 0..len(out) {
                        if out[i].word == w {
                            out[i].n += 1;
                            found = true;
                        }
                    }
                    if not found {
                        push(mut out, Count(word: str[16](w), n: 1));
                    }
                }
                return out;
            }

            test "counts" {
                assert len(counts("a b a")) == 2;
            }
            """);
        Result r = run("fuzz", p.toString());
        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.out().startsWith("tool counts: "), r.out());
        assertTrue(r.out().contains("trap[R0004]: push to a full list (capacity 8)\n"), r.out());
        assertTrue(r.out().contains("  = arguments: {\"text\": \"a b c d e f g h i j k l m n o p q r s t u v w x … (255 bytes)\"}\n"),
            r.out());
        assertTrue(r.out().matches("(?s).*\nfuzz: 1 trap\\(s\\) in \\d+ call\\(s\\)\n"), r.out());

        Result j = run("fuzz", p.toString(), "--json");
        assertEquals(1, j.code());
        Map<?, ?> report = (Map<?, ?>) Json.parse(j.out());
        assertEquals(false, report.get("ok"));
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) report.get("entries")).get(0);
        assertEquals("counts", entry.get("name"));
        assertEquals("tool", entry.get("kind"));
        Map<?, ?> trap = (Map<?, ?>) ((List<?>) entry.get("traps")).get(0);
        assertEquals("R0004", trap.get("code"));
        String text = (String) ((Map<?, ?>) trap.get("arguments")).get("text");
        assertTrue(text.length() <= 256 && text.startsWith("a b c d "), text);
    }

    @Test
    void fuzzLeavesOutTheToolsOwnPreconditionButNotANestedOne() throws IOException {
        Path p = write("pre.al", """
            fn halve(n: i64) -> i64
                requires n % 2 == 0
            {
                return n / 2;
            }

            /// Halves an even count of at most 100.
            tool fn half(n: i64) -> i64
                requires n >= 0 and n <= 100
            {
                return halve(n);
            }
            """);
        Result r = run("fuzz", p.toString(), "--json");
        assertEquals(1, r.code(), r.out());
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) ((Map<?, ?>) Json.parse(r.out())).get("entries")).get(0);
        List<?> traps = (List<?>) entry.get("traps");
        assertEquals(1, traps.size(), r.out());
        Map<?, ?> trap = (Map<?, ?>) traps.get(0);
        assertEquals("R0006", trap.get("code"));
        assertEquals("precondition of `halve` failed: requires n % 2 == 0", trap.get("message"));
        // Only arguments within the tool's own bounds are tried.
        BigInteger n = (BigInteger) ((Map<?, ?>) trap.get("arguments")).get("n");
        assertTrue(n.signum() >= 0 && n.intValue() <= 100 && n.intValue() % 2 == 1, n.toString());
    }

    @Test
    void fuzzFeedsInputToMainAndPassesARobustProgram() throws IOException {
        Path naive = write("naive.al", """
            fn main(input: str[512]) {
                for line in split(input, '\\n') {
                    var parts: list[str[16]; 2] = [];
                    let ok: bool = try_split(mut parts, line, ',');
                    if ok and len(parts) == 2 {
                        print(parts[0]);
                    }
                }
                let all: list[str[512]; 8] = split(input, '\\n');
                print(len(all));
            }
            """);
        Result r = run("fuzz", naive.toString());
        assertEquals(1, r.code(), r.out() + r.err());
        assertTrue(r.out().startsWith("fn main: "), r.out());
        assertTrue(r.out().contains("trap[R0004]: split makes more than 8 pieces"), r.out());
        assertTrue(r.out().contains("  = input: \""), r.out());

        Path robust = write("robust.al", """
            fn main(input: str[512]) {
                var n: i64 = 0;
                for line in split(input, '\\n') {
                    var parts: list[str[16]; 2] = [];
                    let ok: bool = try_split(mut parts, line, ',');
                    if ok and len(parts) == 2 {
                        n += 1;
                    }
                }
                print(n);
            }
            """);
        Result ok = run("fuzz", robust.toString());
        assertEquals(0, ok.code(), ok.out() + ok.err());
        assertTrue(ok.out().matches("fn main: \\d+ call\\(s\\), no traps\nfuzz: no traps in \\d+ call\\(s\\)\n"), ok.out());

        Path none = write("none.al", "fn main() {\n    print(1);\n}\n");
        Result n = run("fuzz", none.toString());
        assertEquals(0, n.code());
        assertEquals("fuzz: " + none + " has no `tool fn` and no fn main(input: str[N]) to call\n", n.out());
    }

    @Test
    void fmtPrintsChecksAndWritesTheLayout() throws IOException {
        String messy = "fn main(){\nprint(1+2);\n}\n";
        String tidy = "fn main() {\n    print(1 + 2);\n}\n";
        Path p = write("messy.al", messy);

        Result printed = run("fmt", p.toString());
        assertEquals(0, printed.code(), printed.err());
        assertEquals(tidy, printed.out());
        assertEquals(messy, Files.readString(p, StandardCharsets.UTF_8), "printing changes nothing");

        Result checked = run("fmt", p.toString(), "--check");
        assertEquals(1, checked.code());
        assertTrue(checked.err().contains("is not formatted; run: alang fmt "), checked.err());

        Result written = run("fmt", p.toString(), "--write");
        assertEquals(0, written.code(), written.err());
        assertEquals(tidy, Files.readString(p, StandardCharsets.UTF_8));
        assertEquals(0, run("fmt", p.toString(), "--check").code());

        Result both = run("fmt", p.toString(), "--check", "--write");
        assertEquals(2, both.code());

        Path bad = write("bad.al", "fn main() {\n    let x: i64 = ;\n}\n");
        Result failed = run("fmt", bad.toString(), "--write");
        assertEquals(1, failed.code());
        assertTrue(failed.err().contains("fmt failed: 1 syntax error(s); nothing was changed"), failed.err());
        assertEquals("fn main() {\n    let x: i64 = ;\n}\n", Files.readString(bad, StandardCharsets.UTF_8));
    }

    @Test
    void fixAppliesEveryMechanicalFixRoundByRound() throws IOException {
        // The parser reports one syntax error at a time, so each round of checking finds the next to fix.
        String broken = """
            struct Point {
                x: i64,
                y: i64,
            }

            fn main() {
                var xs: list[i64; 4] = []
                let n = 2;
                xs.push(n);
                let p: Point = Point { x: 1, y: n };
                if len(xs) > 0 && p.x == 1 || n > 5 {
                    print(xs.length(), " ", True);
                }
            }
            """;
        String fixed = """
            struct Point {
                x: i64,
                y: i64,
            }

            fn main() {
                var xs: list[i64; 4] = [];
                let n: i64 = 2;
                push(mut xs, n);
                let p: Point = Point(x: 1, y: n);
                if (len(xs) > 0 and p.x == 1) or n > 5 {
                    print(len(xs), " ", true);
                }
            }
            """;
        Path p = write("broken.al", broken);
        Result checked = run("check", p.toString());
        assertTrue(checked.err().contains("  = fix: insert `;`\n"), checked.err());
        assertTrue(checked.err().endsWith("; `alang fix " + p + " --write` fixes the 1 marked `= fix:`\n"), checked.err());

        Result printed = run("fix", p.toString());
        assertEquals(0, printed.code(), printed.err());
        assertEquals(fixed, printed.out());
        assertEquals(broken, Files.readString(p, StandardCharsets.UTF_8), "printing changes nothing");
        assertTrue(printed.err().startsWith("fixed[E0001] " + p + ":7:29: insert `;`\nfixed[E0001] " + p + ":8:11: insert `: i64`\n"),
            printed.err());

        Result json = run("fix", p.toString(), "--json");
        Map<?, ?> j = (Map<?, ?>) Json.parse(json.out());
        assertEquals(true, j.get("ok"));
        assertEquals(fixed, j.get("source"));
        assertEquals(false, j.get("written"));
        List<?> done = (List<?>) j.get("fixed");
        assertEquals(9, done.size(), json.out());
        assertEquals("insert `;`", ((Map<?, ?>) done.get(0)).get("description"));
        assertEquals(List.of(Json.obj("line", java.math.BigInteger.valueOf(7), "column", java.math.BigInteger.valueOf(30),
            "length", java.math.BigInteger.ZERO, "replacement", ";")), ((Map<?, ?>) done.get(0)).get("fix"));

        Result written = run("fix", p.toString(), "--write");
        assertEquals(0, written.code(), written.err());
        assertTrue(written.out().endsWith("wrote " + p + ": 9 fix(es)\n"), written.out());
        assertEquals(fixed, Files.readString(p, StandardCharsets.UTF_8));
        Result again = run("fix", p.toString(), "--write");
        assertEquals(p + ": nothing to fix\n", again.out());

        // What has no mechanical fix is reported, and fails.
        Path rest = write("rest.al", "fn main() {\n    let x: i64 = 1 &&& 2;\n}\n");
        Result left = run("fix", rest.toString(), "--write");
        assertEquals(1, left.code());
        assertTrue(left.err().contains("fix: 1 error(s) remain without a mechanical fix"), left.err());
    }
}
