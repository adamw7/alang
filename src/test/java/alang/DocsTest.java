package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.cli.Frontend;
import alang.cli.Main;
import alang.diag.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Keeps the documentation honest: code in the docs compiles, the error catalog is current. */
class DocsTest {
    private static final Pattern BLOCK = Pattern.compile("```alang\\n(.*?)```", Pattern.DOTALL);

    @TestFactory
    Stream<DynamicTest> codeBlocksCompileAndPass() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String doc : List.of("README.md", "AGENTS.md", "docs/SPEC.md")) {
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
