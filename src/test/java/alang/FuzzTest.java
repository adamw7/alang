package alang;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import alang.cli.Frontend;
import alang.diag.Source;
import alang.interp.Interpreter;
import alang.interp.Trap;
import java.io.OutputStream;
import alang.parser.ALangLexer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Mutation fuzzing: deleting, duplicating or swapping tokens of valid programs must never
 * crash the toolchain. Every outcome is either proper diagnostics, or a program that runs
 * to completion or stops with a defined trap.
 * The random generator is seeded, so the test itself is deterministic.
 */
class FuzzTest {
    private static List<String> tokens(String text) {
        ALangLexer lexer = new ALangLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        List<String> out = new ArrayList<>();
        for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) {
            if (t.getChannel() == Token.DEFAULT_CHANNEL) out.add(t.getText());
        }
        return out;
    }

    private static void mustNotCrash(String text, String what) {
        try {
            Frontend.Result r = Frontend.compile(new Source("fuzz.al", text), 0);
            if (!r.ok()) assertTrue(r.diags().hasErrors(), "failed without an error diagnostic: " + what);
            r.diags().renderText();
            if (r.ok()) {
                // Programs that still compile must run to completion or stop with a proper trap.
                for (alang.ast.Ast.Item item : r.program().items) {
                    Interpreter in = new Interpreter(r.diags().source(), OutputStream.nullOutputStream(), 200_000);
                    try {
                        if (item instanceof alang.ast.Ast.FnDecl f && f.name.equals("main")) {
                            in.call(f, new Object[0], f.nameSpan);
                        } else if (item instanceof alang.ast.Ast.TestDecl t) {
                            in.runTest(t);
                        }
                    } catch (Trap expected) {
                        // a defined runtime error
                    }
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            fail("toolchain crashed on mutation (" + what + "):\n" + text, e);
        }
    }

    @TestFactory
    Stream<DynamicTest> mutations() {
        List<Path> files = new ArrayList<>(Harness.files("examples", ".al"));
        files.addAll(Harness.files("tests/run", ".al"));
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            List<String> toks = tokens(Harness.read(p));
            SplittableRandom rnd = new SplittableRandom(p.getFileName().toString().hashCode());
            int n = toks.size();
            int iterations = Integer.getInteger("alang.fuzz", 120);
            for (int k = 0; k < iterations; k++) {
                List<String> m = new ArrayList<>(toks);
                int i = rnd.nextInt(n);
                int j = rnd.nextInt(n);
                String what;
                switch (k % 3) {
                    case 0 -> {
                        m.remove(i);
                        what = "delete " + i;
                    }
                    case 1 -> {
                        m.add(i, toks.get(j));
                        what = "insert " + toks.get(j) + " at " + i;
                    }
                    default -> {
                        m.set(i, toks.get(j));
                        m.set(j, toks.get(i));
                        what = "swap " + i + "," + j;
                    }
                }
                mustNotCrash(String.join(" ", m), what);
            }
        }));
    }
}
