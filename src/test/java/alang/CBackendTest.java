package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import alang.cgen.CGen;
import alang.cli.Frontend;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Differential test: the generated C must behave exactly like the reference interpreter
 * (same stdout bytes, same trap code, message and location) under every available C
 * compiler, and the object code must not reference any allocation or stdio function.
 */
class CBackendTest {
    private static final List<String> FORBIDDEN = List.of(
        "malloc", "calloc", "realloc", "free", "aligned_alloc", "posix_memalign", "alloca",
        "printf", "fprintf", "sprintf", "snprintf", "puts", "fwrite", "fputs", "putchar",
        "time", "clock", "rand", "srand", "getenv");

    /**
     * Each test compiles the program with up to three C compilers and runs each build, then compiles it
     * once more for the symbol check. That is far more than a typical test, and on a cold CI runner the
     * first one took over Harness.TEST_TIMEOUT, so these tests (and each process) get this long.
     */
    private static final Duration COMPILE_AND_RUN_TIMEOUT = Duration.ofSeconds(60);

    private record Proc(int exit, String stdout, String stderr) {}

    private static Proc exec(Path dir, String... cmd) throws IOException, InterruptedException {
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
            .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(p.waitFor(COMPILE_AND_RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "timeout: " + String.join(" ", cmd));
        } finally {
            // Also when the test's own timeout interrupts the wait: never leave a compiler or program running.
            if (p.isAlive()) p.destroyForcibly();
        }
        return new Proc(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    private static boolean available(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        }
        return false;
    }

    private static List<String[]> compilers() {
        List<String[]> out = new ArrayList<>();
        String[] flags = {"-std=c99", "-pedantic", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function"};
        if (available("gcc")) {
            out.add(concat(new String[] {"gcc", "-O2"}, flags));
        }
        if (available("clang")) {
            out.add(concat(new String[] {"clang", "-O0"}, flags));
            out.add(concat(new String[] {"clang", "-O3", "-ffp-contract=fast"}, flags));
        }
        return out;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @TestFactory
    Stream<DynamicTest> differential() {
        List<Path> files = new ArrayList<>(Harness.files("tests/run", ".al"));
        files.addAll(Harness.files("examples", ".al"));
        return files.stream().map(p -> Harness.dynamicTest(p.getFileName().toString(), COMPILE_AND_RUN_TIMEOUT, () -> {
            List<String[]> ccs = compilers();
            assumeTrue(!ccs.isEmpty(), "no C compiler available");
            Frontend.Result r = Harness.compile(p);
            assertTrue(r.ok(), () -> r.diags().renderText());
            Harness.Run expected = Harness.runMain(r);
            Path dir = Files.createTempDirectory("alang-c");
            Path c = dir.resolve("prog.c");
            Files.writeString(c, CGen.generate(r.program()), StandardCharsets.UTF_8);
            for (String[] cc : ccs) {
                List<String> cmd = new ArrayList<>(List.of(cc));
                cmd.addAll(List.of("-o", "prog", "prog.c", "-lm"));
                Proc build = exec(dir, cmd.toArray(new String[0]));
                assertEquals(0, build.exit(), () -> String.join(" ", cc) + ":\n" + build.stderr());
                Proc run = exec(dir, dir.resolve("prog").toString());
                String who = String.join(" ", cc) + " on " + p;
                assertEquals(expected.stdout(), run.stdout(), "stdout differs: " + who);
                if (expected.trap() == null) {
                    assertEquals(0, run.exit(), who + "\n" + run.stderr());
                    assertEquals("", run.stderr(), who);
                } else {
                    assertEquals(1, run.exit(), who);
                    String firstLine = run.stderr().lines().findFirst().orElse("");
                    assertEquals("trap[" + expected.trap().code + "]: " + expected.trap().getMessage(), firstLine, who);
                    String where = r.diags().source().path + ":" + expected.trap().span.line() + ":" + expected.trap().span.col();
                    assertTrue(run.stderr().contains("--> " + where), who + "\n" + run.stderr());
                }
            }
            if (available("nm") && available("gcc")) {
                assertEquals(0, exec(dir, "gcc", "-std=c99", "-O2", "-c", "prog.c", "-o", "prog.o").exit());
                Proc nm = exec(dir, "nm", "-u", "prog.o");
                for (String line : nm.stdout().lines().toList()) {
                    String sym = line.trim().replaceAll("^U\\s+", "").replaceAll("@.*", "");
                    assertTrue(!FORBIDDEN.contains(sym), "generated code references " + sym);
                }
            }
        }));
    }

    /**
     * Room for what the checker's bound leaves out on purpose: the C runtime's start-up frames, scalar
     * temporaries and ABI overhead. The programs in tests/stack overran their bound by 400 KB or more.
     */
    private static final long STACK_SLACK_KB = 64;

    /**
     * The checker's worst-case stack is a promise about the C backend too: each program in tests/stack,
     * built with gcc and clang with and without optimization, must run in a stack of that size (plus
     * {@link #STACK_SLACK_KB}). clang -O0 never reuses a dead variable's stack slot, so every temporary of
     * a function counts, and so does any copy the generated C makes beyond the ones the bound counts.
     */
    @TestFactory
    Stream<DynamicTest> stackBound() {
        return Harness.files("tests/stack", ".al").stream().map(p -> Harness.dynamicTest(p.getFileName().toString(), COMPILE_AND_RUN_TIMEOUT, () -> {
            List<String> ccs = new ArrayList<>();
            if (available("gcc")) ccs.add("gcc");
            if (available("clang")) ccs.add("clang");
            assumeTrue(!ccs.isEmpty() && available("sh"), "no C compiler or shell available");
            Frontend.Result r = Harness.compile(p);
            assertTrue(r.ok(), () -> r.diags().renderText());
            Harness.Run expected = Harness.runMain(r);
            long limitKb = r.check().memory().maxStack() / 1024 + STACK_SLACK_KB;
            Path dir = Files.createTempDirectory("alang-stack");
            Files.writeString(dir.resolve("prog.c"), CGen.generate(r.program()), StandardCharsets.UTF_8);
            for (String cc : ccs) {
                for (String opt : List.of("-O0", "-O2")) {
                    Proc build = exec(dir, cc, opt, "-std=c99", "-o", "prog", "prog.c", "-lm");
                    assertEquals(0, build.exit(), () -> cc + " " + opt + ":\n" + build.stderr());
                    Proc run = exec(dir, "sh", "-c", "ulimit -s " + limitKb + " && exec ./prog");
                    String who = cc + " " + opt + " on " + p + " with a stack of " + limitKb + " KB";
                    assertEquals(0, run.exit(), who + "\n" + run.stderr());
                    assertEquals(expected.stdout(), run.stdout(), who);
                }
            }
        }));
    }
}
