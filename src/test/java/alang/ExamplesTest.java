package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.cli.Frontend;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Every example compiles without any diagnostic, passes its tests, and prints its golden output. */
class ExamplesTest {
    @TestFactory
    Stream<DynamicTest> examples() {
        return Harness.files("examples", ".al").stream().map(p -> Harness.dynamicTest(p.getFileName().toString(), () -> {
            Frontend.Result r = Harness.compile(p);
            assertTrue(r.diags().all().isEmpty(), () -> r.diags().renderText());
            assertEquals(List.of(), Harness.runTests(r));
            Harness.Run run = Harness.runMain(r);
            assertNull(run.trap());
            String name = p.getFileName().toString().replace(".al", ".out");
            assertEquals(Harness.read(Harness.ROOT.resolve("tests/examples").resolve(name)), run.stdout());
        }));
    }

    static Stream<Path> all() {
        return Harness.files("examples", ".al").stream();
    }
}
