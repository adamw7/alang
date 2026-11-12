package alang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import alang.cgen.CGen;
import alang.check.Checker;
import alang.cli.Frontend;
import alang.diag.Source;
import alang.diag.Span;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    /** The file name of the program the tests build. */
    private static final String PROG = WINDOWS ? "prog.exe" : "prog";

    /** Targets that use the Microsoft toolchain (clang on Windows by default), by compiler. */
    private static final Map<String, Boolean> MSVC = new ConcurrentHashMap<>();

    /** A finished process; {@code stderrBytes} keeps the bytes that are not UTF-8, which {@code stderr} replaces. */
    private record Proc(int exit, String stdout, String stderr, byte[] stderrBytes) {}

    private static Proc exec(Path dir, String... cmd) throws IOException, InterruptedException {
        return exec(dir, new byte[0], cmd);
    }

    /** Runs {@code cmd} in {@code dir} with {@code stdin} as its standard input. */
    private static Proc exec(Path dir, byte[] stdin, String... cmd) throws IOException, InterruptedException {
        Path in = dir.resolve("in.txt");
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        Files.write(in, stdin);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
            .redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(p.waitFor(COMPILE_AND_RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "timeout: " + String.join(" ", cmd));
        } finally {
            // Also when the test's own timeout interrupts the wait: never leave a compiler or program running.
            if (p.isAlive()) p.destroyForcibly();
        }
        byte[] errBytes = Files.readAllBytes(err);
        return new Proc(p.exitValue(), new String(Files.readAllBytes(out), StandardCharsets.UTF_8),
            new String(errBytes, StandardCharsets.UTF_8), errBytes);
    }

    private static boolean available(String tool) {
        String file = WINDOWS ? tool + ".exe" : tool;
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, file))) return true;
        }
        return false;
    }

    /** Whether {@code cc} builds with the Microsoft toolchain, which has no libm and sets the stack with /STACK. */
    private static boolean msvc(String cc) {
        return WINDOWS && MSVC.computeIfAbsent(cc, c -> {
            try {
                return exec(Files.createTempDirectory("alang-cc"), c, "-dumpmachine").stdout().contains("msvc");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });
    }

    /** The arguments that link the math library, which the Microsoft C runtime holds: it has no libm. */
    private static List<String> libm(String cc) {
        return msvc(cc) ? List.of() : List.of("-lm");
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

    /**
     * Builds the C for a checked program with each compiler, runs each build, and checks it behaves exactly
     * like the interpreter. Returns the directory that holds prog.c.
     */
    private static Path buildEachAndCompare(Frontend.Result r, List<String[]> ccs) throws IOException, InterruptedException {
        return buildEachAndCompare(r, new byte[0], ccs);
    }

    private static Path buildEachAndCompare(Frontend.Result r, byte[] input, List<String[]> ccs) throws IOException, InterruptedException {
        Harness.Run expected = Harness.runMain(r, input);
        Path dir = Files.createTempDirectory("alang-c");
        Path c = dir.resolve("prog.c");
        Files.writeString(c, CGen.generate(r.program()), StandardCharsets.UTF_8);
        for (String[] cc : ccs) {
            List<String> cmd = new ArrayList<>(List.of(cc));
            cmd.addAll(List.of("-o", PROG, "prog.c"));
            cmd.addAll(libm(cc[0]));
            Proc build = exec(dir, cmd.toArray(new String[0]));
            assertEquals(0, build.exit(), () -> String.join(" ", cc) + ":\n" + build.stderr());
            Proc run = exec(dir, input, dir.resolve(PROG).toString());
            String who = String.join(" ", cc) + " on " + r.diags().source().path;
            assertEquals(expected.stdout(), run.stdout(), "stdout differs: " + who);
            if (expected.trap() == null) {
                assertEquals(0, run.exit(), who + "\n" + run.stderr());
                assertEquals("", run.stderr(), who);
            } else {
                assertEquals(1, run.exit(), who);
                // Byte for byte: the message holds the bytes of str values, which need not be UTF-8.
                byte[] message = expected.trap().messageBytes();
                byte[] head = ("trap[" + expected.trap().code + "]: ").getBytes(StandardCharsets.UTF_8);
                byte[] line = new byte[head.length + message.length + 1];
                System.arraycopy(head, 0, line, 0, head.length);
                System.arraycopy(message, 0, line, head.length, message.length);
                line[line.length - 1] = '\n';
                byte[] got = Arrays.copyOf(run.stderrBytes(), Math.min(line.length, run.stderrBytes().length));
                assertArrayEquals(line, got, () -> who + "\n" + run.stderr());
                // In the file of the trap, which may be one the program imports.
                Span at = expected.trap().span;
                String where = (at.file() != null ? at.file() : r.diags().source()).path + ":" + at.line() + ":" + at.col();
                assertTrue(run.stderr().contains("--> " + where), who + "\n" + run.stderr());
            }
        }
        return dir;
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
            Path dir = buildEachAndCompare(r, Harness.input(p), ccs);
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
     * Every program the checker accepts must compile, however long its chains and however deep it nests
     * within the limit. An else-if chain used to nest each branch in the `else` of the one before: the C
     * backend recursed once per branch (a StackOverflowError at a few thousand), and the C went a bracket
     * deeper per branch, past the 256 that clang accepts at about 130 branches. Each while loop took three
     * brackets, so clang rejected loops nested 90 deep, and it crashed on a match with 10 000 arms, which
     * became an else-if chain that it parses recursively.
     */
    @TestFactory
    Stream<DynamicTest> longChainsAndDeepNesting() {
        StringBuilder chain = new StringBuilder("fn main() {\n    let x: i64 = 4999;\n    if x == 0 {\n        print(0);\n");
        for (int i = 1; i < 5000; i++) {
            chain.append("    } else if x == ").append(i).append(" {\n        print(").append(i).append(");\n");
        }
        chain.append("    } else {\n        print(-1);\n    }\n}\n");
        StringBuilder arms = new StringBuilder("fn main() {\n    let x: i64 = 9999;\n    match x {\n");
        for (int i = 0; i < 10000; i++) arms.append("        ").append(i).append(" => { print(").append(i).append("); }\n");
        arms.append("        _ => { print(-1); }\n    }\n}\n");
        String[][] programs = {
            {"else_if_chain.al", chain.toString()},
            {"match_arms.al", arms.toString()},
            {"nested_loops.al", nestedLoops(127)},
            {"deep_not_contract.al", "fn main()\n    requires " + "not ".repeat(128) + "true\n{\n    print(\"ok\");\n}\n"},
            {"deep_conversion_contract.al", "fn main()\n    requires " + conversions(128) + " > 0.0\n{\n    print(\"ok\");\n}\n"},
        };
        return Stream.of(programs).map(t -> Harness.dynamicTest(t[0], COMPILE_AND_RUN_TIMEOUT, () -> {
            List<String[]> ccs = compilers();
            assumeTrue(!ccs.isEmpty(), "no C compiler available");
            Frontend.Result r = Frontend.compile(new Source(t[0], t[1]), Checker.DEFAULT_MAX_STACK);
            assertTrue(r.ok(), () -> r.diags().renderText());
            buildEachAndCompare(r, ccs);
            if (t[0].equals("nested_loops.al")) {
                // One more level is past the nesting limit, so 127 is as deep as loops get.
                Frontend.Result deeper = Frontend.compile(new Source(t[0], nestedLoops(128)), Checker.DEFAULT_MAX_STACK);
                assertTrue(deeper.diags().renderText().startsWith("error[E0404]: "), deeper.diags().renderText());
            }
        }));
    }

    /**
     * {@code depth} nested conversions ending in an f64: i64(true), then f64, then f32 and f64 in turn. A
     * contract is read before the function's brace, so it may nest all 128 levels; the C used to take two
     * parentheses per conversion (three for the bool), as it did per `not`, past the 256 that clang accepts.
     */
    private static String conversions(int depth) {
        String e = "f64(i64(true))";
        for (int i = 2; i < depth; i++) e = (i % 2 == 0 ? "f32(" : "f64(") + e + ")";
        return e;
    }

    /** {@code depth} while loops, each in the body of the one before. */
    private static String nestedLoops(int depth) {
        return "fn main() {\n    var c: i64 = 0;\n" + "while c < 1 limit 2 {\n".repeat(depth) + "c += 1;\n"
            + "}\n".repeat(depth) + "print(c);\n}\n";
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
     * The shell's {@code ulimit -s} sets the stack, except on Windows, where the linker does.
     */
    @TestFactory
    Stream<DynamicTest> stackBound() {
        return Harness.files("tests/stack", ".al").stream().map(p -> Harness.dynamicTest(p.getFileName().toString(), COMPILE_AND_RUN_TIMEOUT, () -> {
            List<String> ccs = new ArrayList<>();
            if (available("gcc")) ccs.add("gcc");
            if (available("clang")) ccs.add("clang");
            assumeTrue(!ccs.isEmpty() && (WINDOWS || available("sh")), "no C compiler or shell available");
            Frontend.Result r = Harness.compile(p);
            assertTrue(r.ok(), () -> r.diags().renderText());
            Harness.Run expected = Harness.runMain(r);
            long limitKb = r.check().memory().maxStack() / 1024 + STACK_SLACK_KB;
            Path dir = Files.createTempDirectory("alang-stack");
            Files.writeString(dir.resolve("prog.c"), CGen.generate(r.program()), StandardCharsets.UTF_8);
            for (String cc : ccs) {
                for (String opt : List.of("-O0", "-O2")) {
                    List<String> cmd = new ArrayList<>(List.of(cc, opt, "-std=c99", "-o", PROG, "prog.c"));
                    if (WINDOWS) cmd.add((msvc(cc) ? "-Wl,/STACK:" : "-Wl,--stack,") + limitKb * 1024);
                    cmd.addAll(libm(cc));
                    Proc build = exec(dir, cmd.toArray(new String[0]));
                    assertEquals(0, build.exit(), () -> cc + " " + opt + ":\n" + build.stderr());
                    Proc run = WINDOWS
                        ? exec(dir, dir.resolve(PROG).toString())
                        : exec(dir, "sh", "-c", "ulimit -s " + limitKb + " && exec ./prog");
                    String who = cc + " " + opt + " on " + p + " with a stack of " + limitKb + " KB";
                    assertEquals(0, run.exit(), who + "\n" + run.stderr());
                    assertEquals(expected.stdout(), run.stdout(), who);
                }
            }
        }));
    }
}
