package alang.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Strict UTF-8: text that is UTF-8 decodes as the JDK decodes it, and any other byte is refused, not replaced. */
class Utf8Test {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    @Test
    void valid_text_decodes() {
        String s = "aé€😀\u0000";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        assertEquals(s, Utf8.decode(bytes));
        assertEquals(-1, Utf8.firstBad(bytes, bytes.length));
        assertEquals("", Utf8.decode(new byte[0]));
        assertEquals("a", Utf8.decode(bytes, 1));
    }

    @Test
    void every_kind_of_bad_sequence_is_refused_at_its_offset() {
        // A lone continuation byte, a truncated sequence, an overlong encoding, a surrogate, and a code point
        // past U+10FFFF, each after two good bytes.
        int[][] bad = {
            {0x80}, {0xC3}, {0xE2, 0x82}, {0xC0, 0xAF}, {0xE0, 0x80, 0xAF}, {0xED, 0xA0, 0x80}, {0xF4, 0x90, 0x80, 0x80},
            {0xFF}, {0xFE},
        };
        for (int[] tail : bad) {
            byte[] bytes = new byte[2 + tail.length];
            bytes[0] = 'o';
            bytes[1] = 'k';
            for (int i = 0; i < tail.length; i++) bytes[2 + i] = (byte) tail[i];
            assertNull(Utf8.decode(bytes), java.util.Arrays.toString(tail));
            assertEquals(2, Utf8.firstBad(bytes, bytes.length), java.util.Arrays.toString(tail));
        }
    }

    @Test
    void only_the_first_len_bytes_count() {
        byte[] bytes = b('a', 'b', 0xFF);
        assertEquals("ab", Utf8.decode(bytes, 2));
        assertEquals(-1, Utf8.firstBad(bytes, 2));
        assertEquals(2, Utf8.firstBad(bytes, 3));
        // A sequence cut by len is truncated, so refused.
        byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
        assertNull(Utf8.decode(euro, 2));
        assertEquals(0, Utf8.firstBad(euro, 2));
    }
}
