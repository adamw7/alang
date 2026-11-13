package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.Harness;
import alang.check.Checker;
import alang.diag.Diagnostic;
import alang.diag.Edit;
import alang.diag.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** `alang fix` on every program the conformance tests reject: it ends, and leaves nothing it could still fix. */
class FixTest {
    @TestFactory
    Stream<DynamicTest> fixingEndsAndLeavesNoFix() {
        List<Path> files = new ArrayList<>(Harness.files("tests/errors", ".al"));
        files.addAll(Harness.files("examples", ".al"));
        return files.stream().map(p -> Harness.dynamicTest(p.toString(), () -> {
            Source src = new Source(p.toString(), Harness.read(p));
            Fixer.Outcome o = Fixer.run(src, s -> Frontend.compile(s, Checker.DEFAULT_MAX_STACK));
            if (o.fixed().isEmpty()) {
                assertEquals(src.text, o.text(), "no fix, no change");
                return;
            }
            // Every edit stays on its line: the file keeps its line count.
            assertEquals(src.text.split("\n", -1).length, o.text().split("\n", -1).length, o.text());
            List<Edit> left = new ArrayList<>();
            for (Diagnostic d : o.result().diags().all()) {
                if (d.span().file() == null || d.span().file().path.equals(src.path)) left.addAll(d.fixes());
            }
            assertTrue(o.fixed().size() >= Fixer.MAX_ROUNDS || left.isEmpty(), () -> "a fix is left: " + left + "\n" + o.text());
        }));
    }
}
