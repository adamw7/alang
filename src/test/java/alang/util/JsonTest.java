package alang.util;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Json reads a number of more than 800 characters by its first 800 significant digits and a 1 for the rest, since
 * BigDecimal takes time quadratic in the length of the text. Whatever reads the number must not see a difference:
 * the same f64 and f32, and the same integer of at most 20 digits or none, as new BigDecimal(text) gives.
 */
class JsonTest {

    /** xorshift64*: the tests' generators must be seeded explicitly (TestConventionsArchitectureTest). */
    private long state = 0x9E3779B97F4A7C15L;

    private int next(int bound) {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return (int) Long.remainderUnsigned(state * 0x2545F4914F6CDD1DL, bound);
    }

    private void digits(StringBuilder sb, int n, boolean zeros) {
        for (int i = 0; i < n; i++) sb.append(zeros && next(3) > 0 ? '0' : (char) ('0' + next(10)));
    }

    /** JSON text of a number with long runs of digits, often of zeros, near the halfway points of doubles. */
    private String text() {
        StringBuilder sb = new StringBuilder();
        if (next(2) == 0) sb.append('-');
        int shape = next(4);
        if (shape == 0) {
            sb.append('0');
        } else {
            sb.append((char) ('1' + next(9)));
            digits(sb, next(1200), shape == 1);
        }
        if (shape != 3 || next(2) == 0) {
            sb.append('.');
            digits(sb, 1 + next(1200), shape == 2);
        }
        if (next(2) == 0) {
            sb.append(next(2) == 0 ? 'e' : 'E');
            if (next(2) == 0) sb.append(next(2) == 0 ? '-' : '+');
            sb.append(next(700));
        }
        return sb.toString();
    }

    @Test
    void long_numbers_read_as_their_exact_value_would() {
        for (int i = 0; i < 3000; i++) {
            String t = text();
            boolean isFloat = t.contains(".") || t.contains("e") || t.contains("E");
            BigDecimal exact = new BigDecimal(t);
            Object read = Json.parse(t);
            if (isFloat && t.startsWith("-") && exact.signum() == 0) {
                assertEquals(-0.0, read, t);
                continue;
            }
            BigDecimal got = read instanceof BigInteger b ? new BigDecimal(b) : (BigDecimal) read;
            assertEquals(exact.signum(), got.signum(), t);
            if (exact.precision() <= 800) assertEquals(0, exact.compareTo(got), t);
            assertEquals(Double.doubleToRawLongBits(exact.doubleValue()), Double.doubleToRawLongBits(got.doubleValue()), t);
            assertEquals(Float.floatToRawIntBits(exact.floatValue()), Float.floatToRawIntBits(got.floatValue()), t);
            if (exact.signum() != 0) {
                assertEquals((long) exact.precision() - exact.scale(), (long) got.precision() - got.scale(), t);
            }
            // Whether it is an integer matters only to one of at most 20 digits; the integer types hold no more.
            if ((long) exact.precision() - exact.scale() <= 20) assertEquals(isInteger(exact), isInteger(got), t);
        }
    }

    private static boolean isInteger(BigDecimal d) {
        return d.signum() == 0 || d.stripTrailingZeros().scale() <= 0;
    }

    @Test
    void halfway_between_two_doubles_is_decided_by_the_digits_past_800() {
        // 1 + 2^-53 lies halfway between 1 and the next double; ties go to the even one, 1.0.
        String half = new BigDecimal(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE)).divide(new BigDecimal(BigInteger.ONE.shiftLeft(53))).toPlainString();
        String padded = half + "0".repeat(2000);
        assertEquals(1.0, ((BigDecimal) Json.parse(padded)).doubleValue());
        assertEquals(Math.nextUp(1.0), ((BigDecimal) Json.parse(padded + "1")).doubleValue());
        assertEquals(1.0, ((BigDecimal) Json.parse("-" + padded)).negate().doubleValue());
    }

    @Test
    void a_megabyte_of_digits_reads_at_once() {
        String many = "9".repeat(1 << 20);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(Double.POSITIVE_INFINITY, ((BigDecimal) Json.parse(many)).doubleValue());
            assertEquals(1.0, ((BigDecimal) Json.parse("0." + many)).doubleValue());
            assertEquals(0.0, ((BigDecimal) Json.parse("0." + "0".repeat(1 << 20) + "1")).doubleValue());
            assertEquals(-0.0, Json.parse("-0." + "0".repeat(1 << 20)));
        });
    }

    @Test
    void long_numbers_with_exponents_out_of_range_are_refused_as_before() {
        String zeros = "0." + "0".repeat(900) + "1";
        assertThrows(Json.ParseException.class, () -> Json.parse(zeros + "e2147483648"));
        assertThrows(Json.ParseException.class, () -> Json.parse(zeros + "e-2147483648"));
        assertThrows(NumberFormatException.class, () -> new BigDecimal(zeros + "e-2147483648"));
        assertEquals(new BigDecimal(zeros + "e2147483647").doubleValue(), ((BigDecimal) Json.parse(zeros + "e2147483647")).doubleValue());
    }
}
