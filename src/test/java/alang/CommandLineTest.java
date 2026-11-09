package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Arguments that the JVM cannot decode. In the C/POSIX locale the JVM on Linux decodes arguments as
 * ASCII and turns every other byte into U+FFFD, so the CLI must either read a non-ASCII argument
 * correctly (bin/alang switches that locale to C.UTF-8; macOS always decodes UTF-8) or refuse it with
 * a usage error. It must never run on silently changed text.
 */
class CommandLineTest {
    /** "é" in UTF-8, written as printf octal escapes so the bytes do not depend on this JVM's charset. */
    private static final String E_ACUTE = "\\303\\251";

    private record Result(int exit, String stdout, String stderr) {}

    /** Runs {@code alang.cli.Main} in a new JVM, under the given locale, from a shell command line. */
    private static Result alang(Path dir, Map<String, String> locale, String shellArgs) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
            "exec \"$ALANG_JAVA\" -cp \"$ALANG_CP\" alang.cli.Main " + shellArgs).directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("LC_") || k.equals("LANG") || k.equals("JAVA_TOOL_OPTIONS"));
        env.putAll(locale);
        env.put("ALANG_JAVA", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        env.put("ALANG_CP", System.getProperty("java.class.path"));
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out, err);
    }

    private static void sh(Path dir, String command) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", command).directory(dir.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor(), command);
    }

    /** Either the expected output, or exit code 2 with an explanation; nothing else. */
    private static void readCorrectlyOrRefused(Result r, String expected) {
        if (r.exit() == 2) {
            assertTrue(r.stderr().contains("is not valid text in this locale's character set"), r.stderr());
        } else {
            assertEquals(0, r.exit(), r.stderr());
            assertTrue(r.stdout().contains(expected), () -> "expected " + expected + " in: " + r.stdout());
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void nonAsciiArgumentsInTheCLocale(@TempDir Path dir) throws Exception {
        write(dir.resolve("size.al"), "tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n");
        write(dir.resolve("t.al"), "test \"caf\u00e9\" {\n    assert true;\n}\n");
        Map<String, String> c = Map.of("LC_ALL", "C");

        // `call` with a JSON argument: len("é") is 2 bytes. Before the fix the JVM passed two
        // U+FFFD characters (6 bytes) and the call answered {"ok": true, "result": 6}.
        readCorrectlyOrRefused(alang(dir, c,
            "call size.al size \"$(printf '{\"text\": \"" + E_ACUTE + "\"}')\""), "\"result\": 2");

        // `--filter`: before the fix the filter matched nothing, so no test ran and the exit code was 0.
        readCorrectlyOrRefused(alang(dir, c, "test t.al --filter \"$(printf 'caf" + E_ACUTE + "')\""),
            "1 test(s): 1 passed");

        // A non-ASCII file name: before the fix this crashed with an InvalidPathException.
        sh(dir, "cp size.al \"$(printf 'caf" + E_ACUTE + ".al')\"");
        readCorrectlyOrRefused(alang(dir, c, "check \"$(printf 'caf" + E_ACUTE + ".al')\""), "1 function(s)");
    }

    @Test
    void invalidUtf8IsRefused(@TempDir Path dir) throws Exception {
        write(dir.resolve("size.al"), "tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n");
        // \377 is never valid UTF-8, so every JVM decodes it as U+FFFD, even in a UTF-8 locale.
        Result r = alang(dir, Map.of("LC_ALL", "C.UTF-8"), "call size.al size \"$(printf '{\"text\": \"\\377\"}')\"");
        assertEquals(2, r.exit(), r.stdout() + r.stderr());
        assertTrue(r.stderr().contains("argument 4 ("), r.stderr());
        assertEquals("", r.stdout());
    }
}
