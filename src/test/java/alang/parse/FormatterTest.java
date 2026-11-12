package alang.parse;

import static alang.Harness.dynamicTest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.Harness;
import alang.diag.Diagnostics;
import alang.diag.Source;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * `alang fmt`: the examples are laid out the way it lays them out, it changes nothing a second time, it
 * keeps every token and comment, and it puts messy code back the one way.
 */
class FormatterTest {

    private static String format(String path, String text) {
        Source src = new Source(path, text);
        Diagnostics diags = new Diagnostics(src);
        String out = Formatter.format(src, diags);
        assertFalse(diags.hasErrors(), diags::renderText);
        return out;
    }

    /** The examples, and the libraries the tests import, are already formatted. */
    @TestFactory
    Stream<DynamicTest> examplesAreFormatted() {
        List<Path> files = new ArrayList<>(Harness.files("examples", ".al"));
        files.addAll(Harness.files("tests/run/lib", ".al"));
        return files.stream().map(p -> dynamicTest(Harness.name(p), () -> {
            String text = Harness.read(p);
            assertEquals(text, format(Harness.name(p), text), "run: alang fmt " + Harness.name(p) + " --write");
        }));
    }

    /** Every program the tests run formats, and formatting the result again changes nothing. */
    @TestFactory
    Stream<DynamicTest> formattingTwiceChangesNothing() {
        return Harness.files("tests/run", ".al").stream().map(p -> dynamicTest(Harness.name(p), () -> {
            String once = format(Harness.name(p), Harness.read(p));
            assertEquals(once, format(Harness.name(p), once));
        }));
    }

    @Test
    void messyCodeIsLaidOutTheOneWay() {
        String messy = """
            // Totals.
            const  LIMIT:i64=3;   // the most


            struct Pair{a:i64,b:i64,}
            fn total(xs:list[i64;_])->i64{
            var sum:i64=0;
                    for x in xs{sum+=x;}


            return sum;
            }
            fn main(){
              let p:Pair=Pair(a:-1,b:2);
              print(total([1,2,3]),\" \",p.a*-2,\" \",if p.a<0 then 0-p.a else p.a);
              let wide: list[i64; 4] = [
              1,
              2,
              ];
              print(wide[0..1]);
            }
            """;
        String expected = """
            // Totals.
            const LIMIT: i64 = 3;   // the most

            struct Pair { a: i64, b: i64, }

            fn total(xs: list[i64; _]) -> i64 {
                var sum: i64 = 0;
                for x in xs { sum += x; }

                return sum;
            }

            fn main() {
                let p: Pair = Pair(a: -1, b: 2);
                print(total([1, 2, 3]), " ", p.a * -2, " ", if p.a < 0 then 0 - p.a else p.a);
                let wide: list[i64; 4] = [
                    1,
                    2,
                ];
                print(wide[0..1]);
            }
            """;
        assertEquals(expected, format("messy.al", messy));
        assertEquals(expected, format("messy.al", expected));
    }

    @Test
    void typeParametersAndContinuedLinesAreIndented() {
        String text = """
            fn largest<T>(xs: list[T; _]) -> T
            requires len(xs) > 0
            {
            var best: T = xs[0];
            for x in xs {
            if compare(x, best) > 0
            and compare(x, x) == 0 {
            best = x;
            }
            }
            return best;
            }
            """;
        String expected = """
            fn largest<T>(xs: list[T; _]) -> T
                requires len(xs) > 0
            {
                var best: T = xs[0];
                for x in xs {
                    if compare(x, best) > 0
                        and compare(x, x) == 0 {
                        best = x;
                    }
                }
                return best;
            }
            """;
        assertEquals(expected, format("generic.al", text));
    }

    @Test
    void codeWithSyntaxErrorsIsNotFormatted() {
        Source src = new Source("bad.al", "fn main() {\n    let x: i64 = ;\n}\n");
        Diagnostics diags = new Diagnostics(src);
        assertNull(Formatter.format(src, diags));
        assertTrue(diags.hasErrors());
    }
}
