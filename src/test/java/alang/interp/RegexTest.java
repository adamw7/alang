package alang.interp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import org.junit.jupiter.api.Test;

/**
 * The Pike VM of re_match and re_find gives the leftmost match and, among those, the one a backtracking matcher
 * prefers. java.util.regex is such a matcher, so on patterns outside the one documented difference (an empty
 * iteration of a repetition nested in another) the two must find the same text; the rest pins down the syntax,
 * the errors and the limits.
 */
class RegexTest {

    /** xorshift64*: the tests' generators must be seeded explicitly (TestConventionsArchitectureTest). */
    private long state = 0x5DEECE66DL;

    private int next(int bound) {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return (int) Long.remainderUnsigned(state * 0x2545F4914F6CDD1DL, bound);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Regex.Program compile(String p) {
        byte[] b = bytes(p);
        return Regex.compile(b, b.length);
    }

    /** [start, end) of the leftmost match, or null. */
    private static int[] find(String pattern, String text) {
        byte[] t = bytes(text);
        long r = Regex.run(compile(pattern), t, t.length, false, new Meter(0));
        return r < 0 ? null : new int[] {(int) (r >>> 32), (int) r};
    }

    private static boolean matches(String pattern, String text) {
        byte[] t = bytes(text);
        return Regex.run(compile(pattern), t, t.length, true, new Meter(0)) >= 0;
    }

    private static Regex.BadPattern bad(String pattern) {
        return assertThrows(Regex.BadPattern.class, () -> compile(pattern), pattern);
    }

    // ------------------------------------------------------------------ differential

    /** An atom: a literal, `.`, a class or a shorthand, over the alphabet a, b, c and '\n'. */
    private String atom(StringBuilder java) {
        String[][] atoms = {
            {"a", "a"}, {"b", "b"}, {"c", "c"}, {".", "."}, {"[ab]", "[ab]"}, {"[^a]", "[^a]"}, {"[a-b]", "[a-b]"},
            {"\\n", "\\n"}, {"\\w", "\\w"}, {"\\W", "\\W"}, {"\\s", "\\s"},
        };
        String[] a = atoms[next(atoms.length)];
        java.append(a[1]);
        return a[0];
    }

    /**
     * A pattern and its java.util.regex equivalent. Repetition applies only to atoms and to groups that cannot
     * match the empty text, so no repetition ever has an empty iteration and the two engines must agree.
     */
    private String pattern(StringBuilder java, int depth) {
        StringBuilder ours = new StringBuilder();
        int alternatives = depth < 2 && next(4) == 0 ? 2 : 1;
        for (int alt = 0; alt < alternatives; alt++) {
            if (alt > 0) {
                ours.append('|');
                java.append('|');
            }
            int parts = 1 + next(3);
            for (int i = 0; i < parts; i++) {
                boolean group = depth < 2 && next(5) == 0;
                if (group) {
                    ours.append('(');
                    java.append("(?:");
                    // A group of one atom and more: never empty.
                    ours.append(atom(java));
                    ours.append(pattern(java, depth + 1));
                    ours.append(')');
                    java.append(')');
                } else {
                    ours.append(atom(java));
                }
                String rep = switch (next(9)) {
                    case 0 -> "*";
                    case 1 -> "+";
                    case 2 -> "?";
                    case 3 -> "{2}";
                    case 4 -> "{1,3}";
                    case 5 -> "{0,}";
                    default -> "";
                };
                ours.append(rep);
                java.append(rep);
            }
        }
        return ours.toString();
    }

    private String text() {
        StringBuilder sb = new StringBuilder();
        int n = next(12);
        for (int i = 0; i < n; i++) sb.append("abc\n ".charAt(next(5)));
        return sb.toString();
    }

    @Test
    void finds_the_same_match_as_a_backtracking_engine() {
        for (int i = 0; i < 4_000; i++) {
            StringBuilder java = new StringBuilder();
            String ours = pattern(java, 0);
            boolean bol = next(6) == 0;
            boolean eol = next(6) == 0;
            if (bol) ours = "^(" + ours + ")";
            if (eol) ours = (bol ? ours : "(" + ours + ")") + "$";
            String js = (bol ? "\\A(?:" : "(?:") + java + (eol ? ")\\z" : ")");
            Pattern p = Pattern.compile(js, Pattern.UNIX_LINES);
            for (int j = 0; j < 8; j++) {
                String t = text();
                Matcher m = p.matcher(t);
                int[] want = m.find() ? new int[] {m.start(), m.end()} : null;
                assertArrayEquals(want, find(ours, t), "/" + ours + "/ in \"" + t.replace("\n", "\\n") + "\"");
                assertEquals(p.matcher(t).matches(), matches(ours, t), "/" + ours + "/ whole \"" + t.replace("\n", "\\n") + "\"");
            }
        }
    }

    // ------------------------------------------------------------------ syntax

    @Test
    void leftmost_then_preferred_alternative_then_greedy() {
        assertArrayEquals(new int[] {1, 2}, find("a|ab", "xab"));
        assertArrayEquals(new int[] {1, 3}, find("ab|a", "xab"));
        assertArrayEquals(new int[] {0, 4}, find("a*", "aaaab"));
        assertArrayEquals(new int[] {0, 0}, find("a*", "baaa"));
        assertArrayEquals(new int[] {1, 4}, find("a+", "baaa"));
        assertArrayEquals(new int[] {0, 3}, find("a{2,3}", "aaaa"));
        assertArrayEquals(new int[] {0, 2}, find("a{2}", "aaaa"));
        assertArrayEquals(new int[] {0, 4}, find("a{2,}", "aaaa"));
        assertNull(find("a{3}", "aa"));
        assertNull(find("x", ""));
        assertArrayEquals(new int[] {0, 0}, find("", "abc"));
    }

    @Test
    void the_documented_difference_from_perl_on_nested_empty_iterations() {
        // (a*|b)* in "ab": Perl stops at 0..1; here a later empty iteration is not tried first, so 0..2.
        assertArrayEquals(new int[] {0, 2}, find("(a*|b)*", "ab"));
    }

    @Test
    void anchors_match_only_at_the_ends_of_the_text() {
        assertArrayEquals(new int[] {0, 1}, find("^a", "aa"));
        assertNull(find("^a", "ba"));
        assertNull(find("^b", "a\nb"));
        assertArrayEquals(new int[] {1, 2}, find("a$", "aa"));
        assertNull(find("a$", "a\n"));
        assertArrayEquals(new int[] {0, 0}, find("^$", ""));
        assertNull(find("^$", "\n"));
    }

    @Test
    void classes_shorthands_and_escapes() {
        assertTrue(matches("[a-cx-z]+", "abcxyz"));
        assertFalse(matches("[a-cx-z]+", "abcd"));
        assertTrue(matches("[^0-9]+", "abc"));
        assertFalse(matches("[^0-9]+", "a1"));
        assertTrue(matches("[]a]+", "]a]"), "a ] first in a class is a literal");
        assertTrue(matches("[a-]+", "a-a"), "a - last in a class is a literal");
        assertTrue(matches("\\d+\\.\\d+", "3.14"));
        assertFalse(matches("\\d+\\.\\d+", "3x14"));
        assertTrue(matches("\\w+", "a_Z9"));
        assertFalse(matches("\\w", "-"));
        assertTrue(matches("\\s\\S\\D\\W", " xa-"));
        assertTrue(matches("[\\d\\s]+", "1 2\t3"));
        assertTrue(matches("\\t\\r\\n\\0", "\t\r\n\0"));
        assertTrue(matches("\\(\\)\\[\\]\\{\\}\\*\\+\\?\\|\\^\\$\\\\\\.", "()[]{}*+?|^$\\."));
        assertTrue(matches(".", "é".substring(0, 1)), "a class and . work on bytes");
        assertFalse(matches(".", "\n"));
        // Non-ASCII bytes are never \w, \d or \s.
        assertFalse(matches("\\w", "ÿ"));
        assertTrue(matches("\\W", "ÿ"));
    }

    @Test
    void bad_patterns_say_why_and_where() {
        assertEquals("missing )", bad("(ab").getMessage());
        assertEquals("unmatched )", bad("ab)").getMessage());
        assertEquals(2, bad("ab)").at);
        assertEquals("missing ]", bad("[ab").getMessage());
        assertEquals("nothing to repeat", bad("*a").getMessage());
        assertEquals("nothing to repeat", bad("a|+").getMessage());
        assertEquals("nothing to repeat", bad("^*").getMessage());
        assertEquals("nothing to repeat", bad("a**").getMessage());
        assertEquals("bad repetition count", bad("a{2,1}").getMessage());
        assertEquals("bad repetition count", bad("a{101}").getMessage());
        assertEquals("bad repetition count", bad("a{}").getMessage());
        assertEquals("bad repetition count", bad("a{1").getMessage());
        assertEquals("escape at the end of the pattern", bad("a\\").getMessage());
        assertEquals("unknown escape \\q", bad("\\q").getMessage());
        assertEquals("bad range in a class", bad("[z-a]").getMessage());
        assertEquals("bad range in a class", bad("[a-\\d]").getMessage());
        assertEquals(0, bad("x".repeat(Regex.MAX_PATTERN + 1)).at);
    }

    @Test
    void limits_bound_the_size_of_a_program() {
        compile("(".repeat(Regex.MAX_DEPTH) + "a" + ")".repeat(Regex.MAX_DEPTH));
        assertTrue(bad("(".repeat(Regex.MAX_DEPTH + 1) + "a" + ")".repeat(Regex.MAX_DEPTH + 1)).getMessage().contains("nested"));
        compile("\\d".repeat(Regex.MAX_CLASSES));
        assertTrue(bad("\\d".repeat(Regex.MAX_CLASSES + 1)).getMessage().contains("classes"));
        compile("a{" + Regex.MAX_REPEAT + "}");
        assertTrue(bad("(a{100}){100}").getMessage().contains("instructions"));
        compile("x".repeat(Regex.MAX_PATTERN));
    }

    @Test
    void matching_is_linear_in_the_text() {
        // (a|aa)*b on a run of a's with no b is exponential for a backtracking matcher; here each byte costs
        // the same, so the steps grow linearly with the text.
        Regex.Program p = compile("(a|aa)*b");
        long[] costs = new long[3];
        for (int i = 0; i < costs.length; i++) {
            byte[] t = bytes("a".repeat(1000 << i));
            Meter meter = new Meter(0);
            assertEquals(-1, Regex.run(p, t, t.length, false, meter));
            costs[i] = meter.steps();
        }
        assertTrue(costs[1] < 3 * costs[0] && costs[2] < 3 * costs[1], java.util.Arrays.toString(costs));
    }

    @Test
    void run_from_begin_skips_an_empty_match_at_no_empty_at() {
        Regex.Program p = compile("a*");
        byte[] t = bytes("baa");
        Meter m = new Meter(0);
        assertEquals(0L, Regex.run(p, t, t.length, false, 0, -1, m));
        // Passing over the empty match at 0 finds the one at 1, which is not empty.
        assertEquals(1L << 32 | 3, Regex.run(p, t, t.length, false, 0, 0, m));
        assertEquals(2L << 32 | 3, Regex.run(p, t, t.length, false, 2, -1, m));
        assertEquals(3L << 32 | 3, Regex.run(p, t, t.length, false, 3, -1, m));
        // ^ still matches only at 0.
        assertEquals(-1, Regex.run(compile("^a"), bytes("aa"), 2, false, 1, -1, m));
    }
}
