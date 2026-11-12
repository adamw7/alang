package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import alang.cli.Frontend;
import alang.diag.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The same source must always produce byte-identical results: program output, traps,
 * diagnostics and machine-readable reports, regardless of repetition or the host locale.
 */
class DeterminismTest {
    private static final List<Locale> LOCALES = List.of(
        Locale.ROOT,
        Locale.forLanguageTag("de-DE"),
        Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("ar-SA-u-nu-arab"),
        Locale.forLanguageTag("hi-IN-u-nu-deva"));

    private static String fingerprint(Path p) {
        Source src = Harness.source(p);
        Frontend.Result r = Frontend.compile(src, Harness.budget(src.text));
        StringBuilder sb = new StringBuilder();
        sb.append(alang.util.Json.write(r.diags().toJson())).append('\n');
        sb.append(r.diags().renderText());
        if (r.ok()) {
            sb.append(alang.util.Json.write(r.check().memory().toJson())).append('\n');
            if (r.program().function("main") != null) {
                Harness.Run run = Harness.runMain(r, Harness.input(p));
                sb.append(run.stdout());
                if (run.trap() != null) {
                    sb.append(run.trap().code).append(' ').append(run.trap().getMessage()).append(run.trap().trace);
                }
            }
            sb.append(Harness.runTests(r));
        }
        return sb.toString();
    }

    @TestFactory
    Stream<DynamicTest> sameResultEverywhere() {
        List<Path> files = new ArrayList<>(Harness.files("tests/run", ".al"));
        files.addAll(Harness.files("tests/errors", ".al"));
        files.addAll(Harness.files("examples", ".al"));
        return files.stream().map(p -> Harness.dynamicTest(p.toString(), () -> {
            Locale saved = Locale.getDefault();
            try {
                Locale.setDefault(Locale.ROOT);
                String reference = fingerprint(p);
                assertEquals(reference, fingerprint(p), "second run differs");
                for (Locale l : LOCALES) {
                    Locale.setDefault(l);
                    assertEquals(reference, fingerprint(p), "differs under locale " + l);
                }
            } finally {
                Locale.setDefault(saved);
            }
        }));
    }
}
