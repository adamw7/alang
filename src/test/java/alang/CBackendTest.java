package alang;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import alang.cgen.CGen;
import alang.check.Checker;
import alang.cli.Frontend;
import alang.diag.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

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
     * Each test compiles the program with up to three C compilers (and once more for the symbol check), all at
     * once, and runs each build. That is far more than a typical test, and on a cold CI runner the
     * first one took over Harness.TEST_TIMEOUT, so these tests (and each process) get this long.
     */
    private static final Duration COMPILE_AND_RUN_TIMEOUT = Duration.ofSeconds(60);

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    /** The file name of the program the {@code i}th build makes: each build has its own, so builds can run at once. */
    private static String prog(int i) {
        return "prog" + i + (WINDOWS ? ".exe" : "");
    }

    /** Targets that use the Microsoft toolchain (clang on Windows by default), by compiler. */
    private static final Map<String, Boolean> MSVC = new ConcurrentHashMap<>();

    /** A finished process; {@code stderrBytes} keeps the bytes that are not UTF-8, which {@code stderr} replaces. */
    private record Proc(int exit, String stdout, String stderr, byte[] stderrBytes) {}

    /** Numbers the files that hold each process's input and output, so processes can share a directory. */
    private static final java.util.concurrent.atomic.AtomicInteger PROCESSES = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A started process. The tests start the builds of a program together (the compilers take most of this
     * class's time, and a CI runner has several cores) and then wait for each one; {@link #close} stops any
     * that are still running when a test fails or times out.
     */
    private static final class Pending implements AutoCloseable {
        private final Process process;
        private final String[] cmd;
        private final Path out;
        private final Path err;

        Pending(Path dir, byte[] stdin, String... cmd) throws IOException {
            int n = PROCESSES.incrementAndGet();
            Path in = dir.resolve("in" + n + ".txt");
            this.cmd = cmd;
            this.out = dir.resolve("out" + n + ".txt");
            this.err = dir.resolve("err" + n + ".txt");
            Files.write(in, stdin);
            this.process = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        }

        Proc await() throws IOException, InterruptedException {
            try {
                assertTrue(process.waitFor(COMPILE_AND_RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "timeout: " + String.join(" ", cmd));
            } finally {
                // Also when the test's own timeout interrupts the wait: never leave a compiler or program running.
                close();
            }
            byte[] errBytes = Files.readAllBytes(err);
            return new Proc(process.exitValue(), new String(Files.readAllBytes(out), StandardCharsets.UTF_8),
                new String(errBytes, StandardCharsets.UTF_8), errBytes);
        }

        @Override
        public void close() {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    /** Stops every process in {@code ps} that is still running. */
    private static void closeAll(List<Pending> ps) {
        for (Pending p : ps) p.close();
    }

    private static Proc exec(Path dir, String... cmd) throws IOException, InterruptedException {
        return exec(dir, new byte[0], cmd);
    }

    /** Runs {@code cmd} in {@code dir} with {@code stdin} as its standard input. */
    private static Proc exec(Path dir, byte[] stdin, String... cmd) throws IOException, InterruptedException {
        try (Pending p = new Pending(dir, stdin, cmd)) {
            return p.await();
        }
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
        if (available("gcc")) out.add(concat(new String[] {"gcc", "-O2"}, flags));
        if (available("clang")) {
            out.add(concat(new String[] {"clang", "-O0"}, flags));
            out.add(concat(concat(new String[] {"clang", "-O3", "-ffp-contract=fast"}, fusedMultiplyAdd()), flags));
        }
        return out;
    }

    /**
     * The flag that gives clang a fused multiply-add instruction to contract a product and a sum into, where this
     * CPU has one: -mfma on an x86 that has FMA (arm64 always has it). With -ffp-contract=fast, clang then fuses
     * every product it can, and the C must still round each product on its own, as the interpreter does.
     */
    private static String[] fusedMultiplyAdd() {
        String arch = System.getProperty("os.arch");
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (!(arch.equals("amd64") || arch.equals("x86_64")) || !Files.isReadable(cpuinfo)) return new String[0];
        try {
            for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
                if (!line.startsWith("flags")) continue;
                List<String> cpu = Arrays.asList(line.split("\\s+"));
                return cpu.contains("fma") && cpu.contains("avx") ? new String[] {"-mfma"} : new String[0];
            }
        } catch (IOException e) {
            return new String[0];
        }
        return new String[0];
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * Builds the C for a checked program with each compiler, runs each build, and checks it behaves exactly
     * like the interpreter.
     */
    private static void buildEachAndCompare(Frontend.Result r, List<String[]> ccs) throws IOException, InterruptedException {
        buildEachAndCompare(r, new byte[0], ccs, 0, false);
    }

    /**
     * As above, with {@code input} on the standard input. A {@code stack} above 0 is the stack, in bytes, that the
     * program is linked with on Windows, whose default of 1 MB is less than the checker allows (elsewhere the
     * default, usually 8 MB, is enough). With {@code symbols}, gcc also compiles the C to an object file whose
     * undefined symbols must not include an allocation, stdio, clock or environment function.
     *
     * <p>The compilers all start at once, and the interpreter runs while they work: the builds are most of
     * this class's time, and they do not depend on each other. Each build writes its own program.
     */
    private static void buildEachAndCompare(Frontend.Result r, byte[] input, List<String[]> ccs, long stack, boolean symbols)
            throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("alang-c");
        Files.writeString(dir.resolve("prog.c"), CGen.generate(r.program()), StandardCharsets.UTF_8);
        List<Pending> started = new ArrayList<>();
        try {
            for (int i = 0; i < ccs.size(); i++) {
                String[] cc = ccs.get(i);
                List<String> cmd = new ArrayList<>(List.of(cc));
                cmd.addAll(List.of("-o", prog(i), "prog.c"));
                if (WINDOWS && stack > 0) cmd.add((msvc(cc[0]) ? "-Wl,/STACK:" : "-Wl,--stack,") + stack);
                cmd.addAll(libm(cc[0]));
                started.add(new Pending(dir, new byte[0], cmd.toArray(new String[0])));
            }
            Pending object = symbols && available("nm") && available("gcc")
                ? new Pending(dir, new byte[0], "gcc", "-std=c99", "-O2", "-c", "prog.c", "-o", "prog.o")
                : null;
            if (object != null) started.add(object);
            Harness.Run expected = Harness.runMain(r, input);
            for (int i = 0; i < ccs.size(); i++) {
                String[] cc = ccs.get(i);
                Proc build = started.get(i).await();
                assertEquals(0, build.exit(), () -> String.join(" ", cc) + ":\n" + build.stderr());
                Proc run = exec(dir, input, dir.resolve(prog(i)).toString());
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
            if (object != null) {
                Proc compiled = object.await();
                assertEquals(0, compiled.exit(), compiled::stderr);
                Proc nm = exec(dir, "nm", "-u", "prog.o");
                for (String line : nm.stdout().lines().toList()) {
                    String sym = line.trim().replaceAll("^U\\s+", "").replaceAll("@.*", "");
                    assertTrue(!FORBIDDEN.contains(sym), "generated code references " + sym);
                }
            }
        } finally {
            closeAll(started);
        }
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
            buildEachAndCompare(r, Harness.input(p), ccs, 0, true);
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
     * f64(s) caps the exponent it reads, but a str holds up to 1 048 576 digits, which once made up for the cap:
     * text of about 10^8999998 converted to 0.1, the same on both backends. Not in tests/run, where FuzzTest
     * would run a thousand mutations of a program that copies a megabyte at each step.
     */
    @TestFactory
    Stream<DynamicTest> floatTextOfAMillionDigits() {
        String program = """
            fn main() {
                // 0.000...01e10000000, about 10^8999998: too large for an f64.
                var huge: str[1000016] = "0.";
                append(mut huge, repeat('0', 1000000));
                append(mut huge, "1e10000000");
                // 1000...0e-10000000, 10^-9000000: too small.
                var tiny: str[1000016] = "1";
                append(mut tiny, repeat('0', 1000000));
                append(mut tiny, "e-10000000");
                // 1000...0e-1000000 is exactly 1.
                var one: str[1000016] = "1";
                append(mut one, repeat('0', 1000000));
                append(mut one, "e-1000000");
                print(is_float(huge), " ", parse_f64(huge));
                print(is_float(tiny), " ", parse_f64(tiny));
                print(is_float(one), " ", parse_f64(one));
            }
            """;
        return Stream.of(Harness.dynamicTest("float_text_of_a_million_digits.al", COMPILE_AND_RUN_TIMEOUT, () -> {
            Frontend.Result r = Frontend.compile(new Source("float_text_of_a_million_digits.al", program), Checker.DEFAULT_MAX_STACK);
            assertTrue(r.ok(), () -> r.diags().renderText());
            assertEquals("false Option.None\nfalse Option.None\ntrue Option.Some(1.0)\n", Harness.runMain(r, new byte[0]).stdout());
            List<String[]> ccs = compilers();
            assumeTrue(!ccs.isEmpty(), "no C compiler available");
            // Three strs of a megabyte each: more than the 1 MB a Windows program gets unless it asks for more.
            buildEachAndCompare(r, new byte[0], ccs, r.check().memory().maxStack() + STACK_SLACK_KB * 1024, false);
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
            // Every build at once, as in buildEachAndCompare; each writes its own program.
            List<String> who = new ArrayList<>();
            List<Pending> builds = new ArrayList<>();
            try {
                for (String cc : ccs) {
                    for (String opt : List.of("-O0", "-O2")) {
                        List<String> cmd = new ArrayList<>(List.of(cc, opt, "-std=c99", "-o", prog(builds.size()), "prog.c"));
                        if (WINDOWS) cmd.add((msvc(cc) ? "-Wl,/STACK:" : "-Wl,--stack,") + limitKb * 1024);
                        cmd.addAll(libm(cc));
                        who.add(cc + " " + opt);
                        builds.add(new Pending(dir, new byte[0], cmd.toArray(new String[0])));
                    }
                }
                for (int i = 0; i < builds.size(); i++) {
                    String name = who.get(i);
                    Proc build = builds.get(i).await();
                    assertEquals(0, build.exit(), () -> name + ":\n" + build.stderr());
                    Proc run = WINDOWS
                        ? exec(dir, dir.resolve(prog(i)).toString())
                        : exec(dir, "sh", "-c", "ulimit -s " + limitKb + " && exec ./" + prog(i));
                    String what = name + " on " + p + " with a stack of " + limitKb + " KB";
                    assertEquals(0, run.exit(), what + "\n" + run.stderr());
                    assertEquals(expected.stdout(), run.stdout(), what);
                }
            } finally {
                closeAll(builds);
            }
        }));
    }
}
