package alang;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import alang.ast.Ast.FnDecl;
import alang.ast.Ast.TestDecl;
import alang.check.Checker;
import alang.cli.Frontend;
import alang.diag.Source;
import alang.interp.Interpreter;
import alang.interp.Trap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

/** Shared helpers for the test suites. */
public final class Harness {
    private Harness() {}

    public static final Path ROOT = Path.of(System.getProperty("user.dir"));

    public record Run(String stdout, Trap trap) {}

    /**
     * The longest one test may run; the slowest takes under 2 s. JUnit applies the same limit to
     * every {@code @Test} and {@code @TestFactory} method (junit-platform.properties) but not to the
     * dynamic tests a factory returns, so build those with {@link #dynamicTest}.
     */
    public static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    /** A dynamic test that fails once it has run for {@link #TEST_TIMEOUT}. */
    public static DynamicTest dynamicTest(String name, Executable body) {
        return dynamicTest(name, TEST_TIMEOUT, body);
    }

    /**
     * A dynamic test that fails once it has run for {@code timeout}. The body runs on its own thread
     * and is abandoned when time is up, so a body that never checks for interrupts (a checker stuck in
     * a loop) still fails on time instead of hanging the build.
     */
    public static DynamicTest dynamicTest(String name, Duration timeout, Executable body) {
        return DynamicTest.dynamicTest(name, () -> assertTimeoutPreemptively(timeout, body, () -> name));
    }

    /**
     * The path of a file in the repository as the golden files show it: relative to the root, with '/'
     * between names on every OS (Windows writes '\\').
     */
    public static String name(Path p) {
        return ROOT.relativize(p.toAbsolutePath()).toString().replace(File.separatorChar, '/');
    }

    public static Source source(Path p) {
        try {
            return new Source(name(p), Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Path> files(String dir, String ext) {
        try (Stream<Path> s = Files.list(ROOT.resolve(dir))) {
            List<Path> out = new ArrayList<>(s.filter(p -> p.toString().endsWith(ext)).sorted().toList());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern MAX_STACK = Pattern.compile("^// max-stack: (\\d+)$", Pattern.MULTILINE);

    /**
     * The memory budget a program is checked with: the one on its {@code // max-stack: N} line, else the
     * command line's default, so the suites check programs the way {@code alang} does.
     */
    public static long budget(String text) {
        Matcher m = MAX_STACK.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : Checker.DEFAULT_MAX_STACK;
    }

    public static Frontend.Result compile(Path p) {
        Source src = source(p);
        return Frontend.compile(src, budget(src.text));
    }

    /** Runs fn main() and captures stdout. */
    public static Run runMain(Frontend.Result r) {
        FnDecl main = r.program().function("main");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Interpreter in = new Interpreter(r.diags().source(), out, Interpreter.DEFAULT_MAX_STEPS);
        Trap trap = null;
        try {
            in.call(main, new Object[0], main.nameSpan);
        } catch (Trap t) {
            trap = t;
        }
        return new Run(out.toString(StandardCharsets.UTF_8), trap);
    }

    /** Runs every test block; returns failure descriptions (empty when all pass). */
    public static List<String> runTests(Frontend.Result r) {
        List<String> failures = new ArrayList<>();
        for (TestDecl t : r.program().itemsOf(TestDecl.class)) {
            Interpreter in = new Interpreter(r.diags().source(), new ByteArrayOutputStream(), Interpreter.DEFAULT_MAX_STEPS);
            try {
                in.runTest(t);
            } catch (Trap trap) {
                failures.add("test \"" + t.name + "\": " + trap.code + " " + trap.getMessage());
            }
        }
        return failures;
    }
}
