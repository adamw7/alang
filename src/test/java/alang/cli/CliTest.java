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

/** The command line: tool calls with malformed JSON, the nesting limit, and non-ASCII arguments. */
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
}
