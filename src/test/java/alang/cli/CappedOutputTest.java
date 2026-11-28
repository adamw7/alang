package alang.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Captured output keeps its first bytes, counts all of them, and never ends in half a character. */
class CappedOutputTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void output_within_the_limit_is_kept_whole() {
        CappedOutput out = new CappedOutput(10);
        out.write(utf8("hello"), 0, 5);
        out.write('!');
        assertEquals("hello!", out.text());
        assertEquals(6, out.total());
        assertFalse(out.truncated());
    }

    @Test
    void output_past_the_limit_is_counted_but_not_kept() {
        CappedOutput out = new CappedOutput(4);
        out.write(utf8("abcdef"), 0, 6);
        out.write('g');
        out.write(utf8("xyz"), 1, 2);
        assertEquals("abcd", out.text());
        assertEquals(9, out.total());
        assertTrue(out.truncated());
        // Exactly at the limit is not truncated.
        CappedOutput exact = new CappedOutput(3);
        exact.write(utf8("abc"), 0, 3);
        assertFalse(exact.truncated());
    }

    @Test
    void a_character_the_cap_cut_in_half_is_dropped() {
        // é is 2 bytes, € 3, 😀 4: a cap anywhere inside one leaves the text before it.
        String[] chars = {"é", "€", "😀"};
        for (String c : chars) {
            byte[] b = utf8("ab" + c + "z");
            int whole = utf8(c).length;
            for (int limit = 2; limit <= 2 + whole + 1; limit++) {
                CappedOutput out = new CappedOutput(limit);
                out.write(b, 0, b.length);
                String expected = limit < 2 + whole ? "ab" : "ab" + c + (limit > 2 + whole ? "z" : "");
                assertEquals(expected, out.text(), c + " at " + limit);
            }
        }
    }

    @Test
    void a_whole_character_at_the_cap_is_kept() {
        CappedOutput out = new CappedOutput(3);
        out.write(utf8("a€b"), 0, 5);
        assertEquals("a", out.text());
        CappedOutput fits = new CappedOutput(4);
        fits.write(utf8("a€b"), 0, 5);
        assertEquals("a€", fits.text());
    }

    @Test
    void bytes_that_are_not_utf8_are_not_mistaken_for_a_cut_character() {
        // Continuation bytes with no lead byte before them: nothing is dropped beyond what the decoder replaces.
        CappedOutput out = new CappedOutput(4);
        out.write(new byte[] {'a', (byte) 0x80, (byte) 0x80, (byte) 0x80, 'b'}, 0, 5);
        assertTrue(out.truncated());
        assertTrue(out.text().startsWith("a"), out.text());
    }
}
