package alang.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * FloatMath computes the elementary functions itself, so that every backend gives the same bits (the C
 * prelude does the same operations; tests/run/float_math.al checks that they agree). Its exp, log, log10,
 * atan, asin, acos and atan2 are fdlibm's, as Java's StrictMath is, so the two agree bit for bit; sin and cos
 * differ only where ALang reduces a large argument its own way, and tan and pow are ALang's own.
 */
class FloatMathTest {
    private static final int N = 40_000;

    /** How many units in the last place a and b are apart (0 for two NaNs, MAX_VALUE for other mismatches). */
    private static long ulps(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b) || a == b) return 0;
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isInfinite(a) || Double.isInfinite(b)) return Long.MAX_VALUE;
        if ((a < 0) != (b < 0)) return Long.MAX_VALUE;
        return Math.abs(Double.doubleToLongBits(a) - Double.doubleToLongBits(b));
    }

    /** Any double: random bits, ordinary magnitudes, the range of exp, and -1..1, in turn. */
    private static double arg(Random r, int i) {
        return switch (i % 4) {
            case 0 -> Double.longBitsToDouble(r.nextLong());
            case 1 -> (r.nextDouble() - 0.5) * Math.pow(10, r.nextInt(14) - 6);
            case 2 -> (r.nextDouble() - 0.5) * 1500;
            default -> r.nextDouble() * 2 - 1;
        };
    }

    private static void within(String name, long maxUlps, DoubleUnaryOperator mine, DoubleUnaryOperator ref) {
        Random r = new Random(name.hashCode());
        for (int i = 0; i < N; i++) {
            double x = arg(r, i);
            double got = mine.applyAsDouble(x);
            double want = ref.applyAsDouble(x);
            assertTrue(ulps(got, want) <= maxUlps, () -> name + "(" + x + ") = " + got + ", StrictMath gives " + want);
        }
    }

    private static void within2(String name, long maxUlps, DoubleBinaryOperator mine, DoubleBinaryOperator ref) {
        Random r = new Random(name.hashCode());
        for (int i = 0; i < N; i++) {
            double x = arg(r, i);
            double y = arg(r, i + 1);
            double got = mine.applyAsDouble(x, y);
            double want = ref.applyAsDouble(x, y);
            assertTrue(ulps(got, want) <= maxUlps, () -> name + "(" + x + ", " + y + ") = " + got + ", StrictMath gives " + want);
        }
    }

    @Test
    void fdlibmFunctionsAgreeWithStrictMathBitForBit() {
        within("exp", 0, FloatMath::exp, StrictMath::exp);
        within("log", 0, FloatMath::log, StrictMath::log);
        within("log10", 0, FloatMath::log10, StrictMath::log10);
        within("atan", 0, FloatMath::atan, StrictMath::atan);
        within("asin", 0, FloatMath::asin, StrictMath::asin);
        within("acos", 0, FloatMath::acos, StrictMath::acos);
        within2("atan2", 0, FloatMath::atan2, StrictMath::atan2);
    }

    @Test
    void theOthersAreWithinAUnitOrTwo() {
        within("sin", 1, FloatMath::sin, StrictMath::sin);
        within("cos", 1, FloatMath::cos, StrictMath::cos);
        within("tan", 2, FloatMath::tan, StrictMath::tan);
        within("log2", 2, FloatMath::log2, x -> StrictMath.log(x) / StrictMath.log(2));
        // pow(1, NaN) is 1 in C99, which ALang follows, and NaN in Java.
        within2("pow", 1, FloatMath::pow, (x, y) -> x == 1.0 ? 1.0 : StrictMath.pow(x, y));
        within("pow of a small power", 1, x -> FloatMath.pow(Math.abs(x), 1.0 / 3), x -> StrictMath.pow(Math.abs(x), 1.0 / 3));
        within("pow of an integer power", 1, x -> FloatMath.pow(x, -7.0), x -> StrictMath.pow(x, -7.0));
    }

    @Test
    void exactWhereTheResultIsRepresentable() {
        assertEquals(1024.0, FloatMath.pow(2.0, 10.0));
        assertEquals(-8.0, FloatMath.pow(-2.0, 3.0));
        assertEquals(Double.MIN_VALUE, FloatMath.pow(2.0, -1074.0));
        assertEquals(1e308, FloatMath.pow(10.0, 308.0));
        assertEquals(Math.sqrt(2.0), FloatMath.pow(2.0, 0.5));
        for (int k = -1074; k <= 1023; k++) assertEquals(k, FloatMath.log2(Math.scalb(1.0, k)));
        for (int p = 0; p <= 22; p++) assertEquals(p, FloatMath.log10(Double.parseDouble("1e" + p)));
        assertEquals(1.0, FloatMath.exp(0.0));
        assertEquals(0.0, FloatMath.log(1.0));
    }

    @Test
    void specialCasesFollowC99() {
        assertEquals(1.0, FloatMath.pow(Double.NaN, 0.0));
        assertEquals(1.0, FloatMath.pow(1.0, Double.NaN));
        assertTrue(Double.isNaN(FloatMath.pow(-8.0, 1.0 / 3)));
        assertEquals(Double.POSITIVE_INFINITY, FloatMath.pow(0.0, -1.0));
        assertEquals(Double.NEGATIVE_INFINITY, FloatMath.pow(-0.0, -3.0));
        assertEquals(-0.0, FloatMath.pow(Double.NEGATIVE_INFINITY, -3.0));
        assertEquals(0.0, FloatMath.pow(0.5, Double.POSITIVE_INFINITY));
        assertEquals(1.0, FloatMath.pow(-1.0, Double.NEGATIVE_INFINITY));
        assertEquals(Double.NEGATIVE_INFINITY, FloatMath.log(0.0));
        assertTrue(Double.isNaN(FloatMath.log(-1.0)));
        assertTrue(Double.isNaN(FloatMath.sin(Double.POSITIVE_INFINITY)));
        assertEquals(Math.PI, FloatMath.atan2(0.0, -1.0));
        assertEquals(-Math.PI, FloatMath.atan2(-0.0, -1.0));
    }
}
