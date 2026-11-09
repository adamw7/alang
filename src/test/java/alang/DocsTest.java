package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.cli.Frontend;
import alang.cli.Main;
import alang.diag.Source;
import alang.interp.Trap;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Keeps the documentation honest: code in the docs compiles, diagnostics and traps shown in the docs
 * match the toolchain's output, and the error catalog is current.
 */
class DocsTest {
    private static final Pattern BLOCK = Pattern.compile("```alang\\n(.*?)```", Pattern.DOTALL);
    /** A program the docs show failing: ```alang-rejected NAME.al (checker) or ```alang-traps NAME.al (run). */
    private static final Pattern FAILING = Pattern.compile("```alang-(rejected|traps) (\\S+)\\n(.*?)```", Pattern.DOTALL);
    /** The toolchain output shown for it, in the ```text block right after. */
    private static final Pattern OUTPUT = Pattern.compile("\\s*```text\\n(.*?)```", Pattern.DOTALL);

    @TestFactory
    Stream<DynamicTest> codeBlocksCompileAndPass() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String doc : List.of("README.md", "AGENTS.md", "docs/SPEC.md", "docs/COMPARISON.md")) {
            Matcher m = BLOCK.matcher(Harness.read(Harness.ROOT.resolve(doc)));
            int n = 0;
            while (m.find()) {
                String code = m.group(1);
                String name = doc + " block " + ++n;
                tests.add(Harness.dynamicTest(name, () -> {
                    Frontend.Result r = Frontend.compile(new Source(name, code), 0);
                    assertTrue(r.diags().all().isEmpty(), () -> r.diags().renderText());
                    assertEquals(List.of(), Harness.runTests(r));
                    if (r.program().function("main") != null) {
                        assertEquals(null, Harness.runMain(r).trap());
                    }
                }));
            }
        }
        assertTrue(tests.size() >= 5, "expected code blocks in the docs");
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> shownDiagnosticsAndTrapsAreCurrent() {
        List<DynamicTest> tests = new ArrayList<>();
        String doc = "docs/COMPARISON.md";
        String text = Harness.read(Harness.ROOT.resolve(doc));
        Matcher m = FAILING.matcher(text);
        while (m.find()) {
            boolean rejected = m.group(1).equals("rejected");
            String file = m.group(2);
            String code = m.group(3);
            Matcher out = OUTPUT.matcher(text).region(m.end(), text.length());
            String shown = out.lookingAt() ? out.group(1) : null;
            tests.add(Harness.dynamicTest(doc + " " + file, () -> {
                assertNotNull(shown, file + " must be followed by a ```text block with the toolchain's output");
                Frontend.Result r = Frontend.compile(new Source(file, code), 0);
                String actual;
                if (rejected) {
                    assertFalse(r.ok(), file + " is shown as rejected, but it compiles");
                    actual = r.diags().renderText();
                } else {
                    assertTrue(r.diags().all().isEmpty(), () -> r.diags().renderText());
                    Trap trap = Harness.runMain(r).trap();
                    assertNotNull(trap, file + " is shown as trapping, but runs to completion");
                    actual = Main.renderTrap(r.diags().source(), trap);
                }
                assertEquals(actual.strip(), shown.strip(), doc + ": the output shown for " + file + " is stale");
            }));
        }
        assertTrue(tests.size() >= 3, "expected failing examples in " + doc);
        return tests.stream();
    }

    @Test
    void errorCatalogIsGenerated() {
        assertEquals(Main.errorsMarkdown(), Harness.read(Harness.ROOT.resolve("docs/ERRORS.md")),
            "docs/ERRORS.md is stale; run: bin/alang explain markdown > docs/ERRORS.md");
    }

    @Test
    void grammarIsTheSourceOfTheParser() {
        String pom = Harness.read(Harness.ROOT.resolve("pom.xml"));
        assertTrue(pom.contains("<sourceDirectory>${project.basedir}/grammar</sourceDirectory>"));
        String g4 = Harness.read(Harness.ROOT.resolve("grammar/ALang.g4"));
        assertTrue(g4.contains("grammar ALang;"));
        assertTrue(!g4.contains("@header") && !g4.contains("@members"), "grammar must stay target-independent");
    }
}
