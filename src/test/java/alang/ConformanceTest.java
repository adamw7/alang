package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.cli.Frontend;
import alang.diag.Diagnostic;
import alang.diag.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * tests/run/NAME.al     must compile cleanly; stdout must equal NAME.out;
 *                       NAME.trap (if present) holds the expected trap code and line:col, and optionally
 *                       the trap's message on a second line.
 * tests/errors/NAME.al  first line `// expect: CODE...` lists the exact set of diagnostic codes;
 *                       an optional `// max-stack: N` line sets the memory budget (default: the
 *                       command line's, 8 MiB).
 *                       NAME.err (if present) holds the diagnostics exactly as `alang check` prints them
 *                       (bin/alang check tests/errors/NAME.al), so that messages and hints are checked too.
 */
class ConformanceTest {

    @TestFactory
    Stream<DynamicTest> runTests() {
        return Harness.files("tests/run", ".al").stream().map(p -> Harness.dynamicTest(p.getFileName().toString(), () -> {
            Frontend.Result r = Harness.compile(p);
            assertTrue(r.diags().all().isEmpty(), () -> r.diags().renderText());
            Harness.Run run = Harness.runMain(r);
            String base = p.toString().substring(0, p.toString().length() - 3);
            assertEquals(Harness.read(Path.of(base + ".out")), run.stdout(), "stdout of " + p);
            Path trapFile = Path.of(base + ".trap");
            if (Files.exists(trapFile)) {
                assertNotNull(run.trap(), "expected a trap");
                String got = run.trap().code + " " + run.trap().span.line() + ":" + run.trap().span.col();
                String[] want = Harness.read(trapFile).strip().split("\n", 2);
                assertEquals(want[0], got);
                if (want.length > 1) assertEquals(want[1], run.trap().getMessage(), "trap message of " + p);
            } else {
                assertNull(run.trap(), () -> "unexpected trap: " + run.trap().getMessage());
            }
            assertEquals(List.of(), Harness.runTests(r));
        }));
    }

    private static final Pattern EXPECT = Pattern.compile("^// expect: (.*)$", Pattern.MULTILINE);
    @TestFactory
    Stream<DynamicTest> errorTests() {
        return Harness.files("tests/errors", ".al").stream().map(p -> Harness.dynamicTest(p.getFileName().toString(), () -> {
            String text = Harness.read(p);
            Matcher m = EXPECT.matcher(text);
            assertTrue(m.find(), "missing // expect: line");
            TreeSet<String> expected = new TreeSet<>(List.of(m.group(1).trim().split("\\s+")));
            String name = Harness.name(p);
            Frontend.Result r = Frontend.compile(new Source(name, text), Harness.budget(text));
            TreeSet<String> got = new TreeSet<>();
            for (Diagnostic d : r.diags().all()) got.add(d.code().name());
            assertEquals(expected, got, () -> r.diags().renderText());
            for (Diagnostic d : r.diags().all()) {
                assertTrue(d.span().line() > 0, "diagnostic without location: " + d);
            }
            Path errFile = Path.of(p.toString().substring(0, p.toString().length() - 3) + ".err");
            if (Files.exists(errFile)) {
                assertEquals(Harness.read(errFile), r.diags().renderText(), "diagnostics of " + name);
            }
        }));
    }
}
