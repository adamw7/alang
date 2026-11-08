package alang;

import alang.ast.Ast.FnDecl;
import alang.ast.Ast.TestDecl;
import alang.cli.Frontend;
import alang.diag.Source;
import alang.interp.Interpreter;
import alang.interp.Trap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Shared helpers for the test suites. */
public final class Harness {
    private Harness() {}

    public static final Path ROOT = Path.of(System.getProperty("user.dir"));

    public record Run(String stdout, Trap trap) {}

    public static Source source(Path p) {
        try {
            return new Source(ROOT.relativize(p.toAbsolutePath()).toString(), Files.readString(p, StandardCharsets.UTF_8));
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

    public static Frontend.Result compile(Path p) {
        return Frontend.compile(source(p), 0);
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
