package alang.interp;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import org.junit.jupiter.api.Test;

/**
 * The text of floats: fixed() rounds the exact binary value half to even, and print writes at most six
 * decimals without trailing zeros. The C backend writes the same text, so it is pinned down here byte for byte.
 */
class FormatTest {

    /** xorshift64*: the tests' generators must be seeded explicitly (TestConventionsArchitectureTest). */
    private long state = 0xC0FFEE1234567L;

    private long next() {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return state * 0x2545F4914F6CDD1DL;
    }

    @Test
    void fixed_rounds_the_exact_binary_value_half_to_even() {
        assertEquals("0", Format.formatFixed(0.0, 0));
        assertEquals("0.00", Format.formatFixed(0.0, 2));
        assertEquals("-0.00", Format.formatFixed(-0.0, 2));
        assertEquals("2", Format.formatFixed(2.5, 0));
        assertEquals("4", Format.formatFixed(3.5, 0));
        assertEquals("-2", Format.formatFixed(-2.5, 0));
        // 2.675 is just below 2.675 in binary, so it rounds down, as C's printf does.
        assertEquals("2.67", Format.formatFixed(2.675, 2));
        assertEquals("0.1000000000000000055511", Format.formatFixed(0.1, 22));
        assertEquals("1.00", Format.formatFixed(0.999, 2));
        assertEquals("nan", Format.formatFixed(Double.NaN, 3));
        assertEquals("inf", Format.formatFixed(Double.POSITIVE_INFINITY, 3));
        assertEquals("-inf", Format.formatFixed(Double.NEGATIVE_INFINITY, 0));
        assertEquals("179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368",
            Format.formatFixed(Double.MAX_VALUE, 0));
    }

    @Test
    void fixed_agrees_with_big_decimal_on_random_doubles() {
        for (int i = 0; i < 20_000; i++) {
            double d = Double.longBitsToDouble(next());
            if (!Double.isFinite(d) || Math.abs(d) > 1e30 || Math.abs(d) < 1e-30) d = (next() >> 11) * 0x1.0p-30;
            int decimals = (int) Long.remainderUnsigned(next(), Format.MAX_DECIMALS + 1);
            String s = Format.formatFixed(d, decimals);
            BigDecimal expected = new BigDecimal(d).setScale(decimals, RoundingMode.HALF_EVEN);
            assertEquals(0, expected.compareTo(new BigDecimal(s)), d + " to " + decimals);
            int point = s.indexOf('.');
            assertEquals(decimals, point < 0 ? 0 : s.length() - point - 1, s);
        }
    }

    @Test
    void print_writes_at_most_six_decimals_without_trailing_zeros() {
        assertEquals("0.3", Format.formatFloat(0.1 + 0.2));
        assertEquals("1.0", Format.formatFloat(1.0));
        assertEquals("-1.0", Format.formatFloat(-1.0));
        assertEquals("0.0", Format.formatFloat(0.0));
        assertEquals("-0.0", Format.formatFloat(-0.0));
        assertEquals("0.333333", Format.formatFloat(1.0 / 3));
        assertEquals("0.666667", Format.formatFloat(2.0 / 3));
        assertEquals("0.0", Format.formatFloat(1e-7));
        assertEquals("0.000001", Format.formatFloat(1e-6));
        assertEquals("1000000.0", Format.formatFloat(1e6));
        assertEquals("100000000000000000000.0", Format.formatFloat(1e20));
        assertEquals("nan", Format.formatFloat(Double.NaN));
        assertEquals("inf", Format.formatFloat(Double.POSITIVE_INFINITY));
        assertEquals("-inf", Format.formatFloat(Double.NEGATIVE_INFINITY));
        for (int i = 0; i < 20_000; i++) {
            double d = (next() >> 20) * 0x1.0p-24;
            String s = Format.formatFloat(d);
            String fixed = Format.formatFixed(d, 6);
            assertTrue(fixed.startsWith(s), s + " / " + fixed);
            assertTrue(fixed.substring(s.length()).chars().allMatch(c -> c == '0'), s + " / " + fixed);
            assertTrue(s.endsWith(".0") || !s.endsWith("0"), s);
        }
    }
}
