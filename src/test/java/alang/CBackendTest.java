package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import alang.cgen.CGen;
import alang.cli.Frontend;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

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

    private record Proc(int exit, String stdout, String stderr) {}

    private static Proc exec(Path dir, String... cmd) throws IOException, InterruptedException {
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
            .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "timeout: " + String.join(" ", cmd));
        return new Proc(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8), Files.readString(err, StandardCharsets.UTF_8));
    }

    private static boolean available(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (Files.isExecutable(Path.of(dir, tool))) return true;
        }
        return false;
    }

    private static List<String[]> compilers() {
        List<String[]> out = new ArrayList<>();
        String[] flags = {"-std=c99", "-pedantic", "-Wall", "-Wextra", "-Werror", "-Wno-unused-function"};
        if (available("gcc")) {
            out.add(concat(new String[] {"gcc", "-O2"}, flags));
        }
        if (available("clang")) {
            out.add(concat(new String[] {"clang", "-O0"}, flags));
            out.add(concat(new String[] {"clang", "-O3", "-ffp-contract=fast"}, flags));
        }
        return out;
    }

    private static String[] concat(String[] a, String[] b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @TestFactory
    Stream<DynamicTest> differential() {
        List<Path> files = new ArrayList<>(Harness.files("tests/run", ".al"));
        files.addAll(Harness.files("examples", ".al"));
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            List<String[]> ccs = compilers();
            assumeTrue(!ccs.isEmpty(), "no C compiler available");
            Frontend.Result r = Harness.compile(p);
            assertTrue(r.ok(), () -> r.diags().renderText());
            Harness.Run expected = Harness.runMain(r);
            Path dir = Files.createTempDirectory("alang-c");
            Path c = dir.resolve("prog.c");
            Files.writeString(c, CGen.generate(r.program()), StandardCharsets.UTF_8);
            for (String[] cc : ccs) {
                List<String> cmd = new ArrayList<>(List.of(cc));
                cmd.addAll(List.of("-o", "prog", "prog.c", "-lm"));
                Proc build = exec(dir, cmd.toArray(new String[0]));
                assertEquals(0, build.exit(), () -> String.join(" ", cc) + ":\n" + build.stderr());
                Proc run = exec(dir, dir.resolve("prog").toString());
                String who = String.join(" ", cc) + " on " + p;
                assertEquals(expected.stdout(), run.stdout(), "stdout differs: " + who);
                if (expected.trap() == null) {
                    assertEquals(0, run.exit(), who + "\n" + run.stderr());
                    assertEquals("", run.stderr(), who);
                } else {
                    assertEquals(1, run.exit(), who);
                    String firstLine = run.stderr().lines().findFirst().orElse("");
                    assertEquals("trap[" + expected.trap().code + "]: " + expected.trap().getMessage(), firstLine, who);
                    String where = r.diags().source().path + ":" + expected.trap().span.line() + ":" + expected.trap().span.col();
                    assertTrue(run.stderr().contains("--> " + where), who + "\n" + run.stderr());
                }
            }
            if (available("nm") && available("gcc")) {
                assertEquals(0, exec(dir, "gcc", "-std=c99", "-O2", "-c", "prog.c", "-o", "prog.o").exit());
                Proc nm = exec(dir, "nm", "-u", "prog.o");
                for (String line : nm.stdout().lines().toList()) {
                    String sym = line.trim().replaceAll("^U\\s+", "").replaceAll("@.*", "");
                    assertTrue(!FORBIDDEN.contains(sym), "generated code references " + sym);
                }
            }
        }));
    }
}
