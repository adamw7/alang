package alang.interp;

import static org.junit.jupiter.api.Assertions.*;

import alang.diag.Code;
import alang.types.PrimKind;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * IntOps carries every integer width in a long and must give, for each operation, exactly the mathematical
 * result when it fits the type and an R0001 trap when it does not. BigInteger is the reference: these tests
 * run each operation on the boundary values of every kind and on seeded random ones, and compare.
 */
class IntOpsTest {

    private static final List<PrimKind> INTS = Arrays.stream(PrimKind.values()).filter(PrimKind::isInt).toList();

    /** xorshift64*: the tests' generators must be seeded explicitly (TestConventionsArchitectureTest). */
    private long state = 0x2545F4914F6CDD1DL;

    private long next() {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return state * 0x2545F4914F6CDD1DL;
    }

    /** The long that carries {@code v} of kind {@code k}: u64 as its bit pattern, the others as their value. */
    private static long carried(BigInteger v) {
        return v.longValue();
    }

    private static BigInteger value(PrimKind k, long v) {
        return IntOps.toBig(k, v);
    }

    /** A random value of kind {@code k}: often a boundary, otherwise any. */
    private long sample(PrimKind k) {
        long r = next();
        BigInteger min = k.min();
        BigInteger max = k.max();
        return switch ((int) Long.remainderUnsigned(r, 8)) {
            case 0 -> carried(min);
            case 1 -> carried(max);
            case 2 -> 0;
            case 3 -> k.signed ? -1 : 1;
            case 4 -> carried(max.subtract(BigInteger.valueOf(Long.remainderUnsigned(next(), 4))));
            case 5 -> carried(min.add(BigInteger.valueOf(Long.remainderUnsigned(next(), 4))));
            case 6 -> carried(BigInteger.valueOf(Long.remainderUnsigned(next(), 16)).subtract(k.signed ? BigInteger.valueOf(8) : BigInteger.ZERO));
            default -> {
                BigInteger span = max.subtract(min).add(BigInteger.ONE);
                yield carried(new BigInteger(Long.toUnsignedString(next())).mod(span).add(min));
            }
        };
    }

    private interface Op {
        long apply(PrimKind k, long a, long b);
    }

    /** Checks op against the exact result: equal when it fits, an R0001 trap when it does not. */
    private static void agrees(PrimKind k, long a, long b, BigInteger exact, Op op, String what) {
        String where = k.name + " " + value(k, a) + " " + what + " " + value(k, b);
        if (k.fits(exact)) {
            assertEquals(exact, value(k, op.apply(k, a, b)), where);
        } else {
            Trap t = assertThrows(Trap.class, () -> op.apply(k, a, b), where);
            assertEquals(Code.R0001, t.code, where);
            assertTrue(t.getMessage().contains(k.name), t.getMessage());
        }
    }

    @Test
    void add_sub_mul_agree_with_exact_arithmetic_on_every_kind() {
        for (PrimKind k : INTS) {
            for (int i = 0; i < 20_000; i++) {
                long a = sample(k);
                long b = sample(k);
                BigInteger x = value(k, a);
                BigInteger y = value(k, b);
                agrees(k, a, b, x.add(y), (kk, p, q) -> IntOps.add(kk, p, q, null), "+");
                agrees(k, a, b, x.subtract(y), (kk, p, q) -> IntOps.sub(kk, p, q, null), "-");
                agrees(k, a, b, x.multiply(y), (kk, p, q) -> IntOps.mul(kk, p, q, null), "*");
            }
        }
    }

    @Test
    void div_and_rem_truncate_toward_zero_and_trap_on_zero() {
        for (PrimKind k : INTS) {
            for (int i = 0; i < 20_000; i++) {
                long a = sample(k);
                long b = sample(k);
                BigInteger x = value(k, a);
                BigInteger y = value(k, b);
                if (y.signum() == 0) {
                    assertEquals(Code.R0002, assertThrows(Trap.class, () -> IntOps.div(k, a, b, null)).code);
                    assertEquals(Code.R0002, assertThrows(Trap.class, () -> IntOps.rem(k, a, b, null)).code);
                    continue;
                }
                agrees(k, a, b, x.divide(y), (kk, p, q) -> IntOps.div(kk, p, q, null), "/");
                // The remainder always fits, even of MIN % -1, and keeps the sign of the dividend.
                assertEquals(x.remainder(y), value(k, IntOps.rem(k, a, b, null)), k.name + " " + x + " % " + y);
            }
        }
    }

    @Test
    void neg_and_abs_trap_only_on_the_minimum_of_a_signed_kind() {
        for (PrimKind k : INTS) {
            if (!k.signed) continue;
            long min = carried(k.min());
            Trap neg = assertThrows(Trap.class, () -> IntOps.neg(k, min, null));
            assertTrue(neg.getMessage().contains("`-`"), neg.getMessage());
            Trap abs = assertThrows(Trap.class, () -> IntOps.abs(k, min, null));
            assertTrue(abs.getMessage().contains("`abs`"), abs.getMessage());
            for (int i = 0; i < 5_000; i++) {
                long a = sample(k);
                if (a == min) continue;
                assertEquals(value(k, a).negate(), value(k, IntOps.neg(k, a, null)));
                assertEquals(value(k, a).abs(), value(k, IntOps.abs(k, a, null)));
            }
        }
        // Negating an unsigned value fits only for 0.
        assertEquals(0, IntOps.neg(PrimKind.U8, 0, null));
        assertEquals(Code.R0001, assertThrows(Trap.class, () -> IntOps.neg(PrimKind.U8, 1, null)).code);
        assertEquals(Code.R0001, assertThrows(Trap.class, () -> IntOps.neg(PrimKind.U64, 1, null)).code);
    }

    @Test
    void bitwise_not_stays_within_the_width() {
        for (PrimKind k : INTS) {
            for (int i = 0; i < 5_000; i++) {
                long a = sample(k);
                long r = IntOps.bnot(k, a);
                assertTrue(k.fits(value(k, r)), k.name + " ~" + value(k, a));
                assertEquals(a, IntOps.bnot(k, r), k.name + " ~~" + value(k, a));
                // In two's complement ~x is -x - 1; for an unsigned kind it is max - x.
                BigInteger expected = k.signed ? value(k, a).negate().subtract(BigInteger.ONE) : k.max().subtract(value(k, a));
                assertEquals(expected, value(k, r), k.name + " ~" + value(k, a));
            }
        }
    }

    @Test
    void shifts_wrap_to_the_width_and_trap_on_an_amount_out_of_range() {
        for (PrimKind k : INTS) {
            BigInteger modulus = BigInteger.ONE.shiftLeft(k.bits);
            for (int i = 0; i < 5_000; i++) {
                long a = sample(k);
                int n = (int) Long.remainderUnsigned(next(), k.bits);
                BigInteger left = value(k, a).shiftLeft(n).mod(modulus);
                if (k.signed && left.testBit(k.bits - 1)) left = left.subtract(modulus);
                assertEquals(left, value(k, IntOps.shl(k, PrimKind.I64, a, n, null)), k.name + " " + value(k, a) + " << " + n);
                // A right shift is arithmetic for signed kinds (floor division by 2^n), logical for unsigned ones.
                assertEquals(value(k, a).shiftRight(n), value(k, IntOps.shr(k, PrimKind.I64, a, n, null)), k.name + " >> " + n);
            }
            for (long bad : new long[] {-1, k.bits, k.bits + 1, Long.MAX_VALUE, Long.MIN_VALUE}) {
                Trap t = assertThrows(Trap.class, () -> IntOps.shl(k, PrimKind.I64, 1, bad, null));
                assertEquals(Code.R0012, t.code);
                assertTrue(t.getMessage().contains("0.." + (k.bits - 1)), t.getMessage());
                assertEquals(Code.R0012, assertThrows(Trap.class, () -> IntOps.shr(k, PrimKind.I64, 1, bad, null)).code);
            }
            // A u64 amount is unsigned: its bit pattern -1 is 2^64 - 1, too large, and named so.
            Trap huge = assertThrows(Trap.class, () -> IntOps.shl(k, PrimKind.U64, 1, -1, null));
            assertTrue(huge.getMessage().contains("18446744073709551615"), huge.getMessage());
        }
    }

    @Test
    void compare_and_to_string_read_u64_as_unsigned() {
        assertTrue(IntOps.compare(PrimKind.U64, -1, 1) > 0);
        assertTrue(IntOps.compare(PrimKind.I64, -1, 1) < 0);
        assertEquals(0, IntOps.compare(PrimKind.U64, 7, 7));
        assertEquals("18446744073709551615", IntOps.toString(PrimKind.U64, -1));
        assertEquals("-1", IntOps.toString(PrimKind.I64, -1));
        assertEquals(new BigInteger("9223372036854775808"), IntOps.toBig(PrimKind.U64, Long.MIN_VALUE));
    }

    @Test
    void conversions_between_kinds_keep_the_value_or_trap() {
        for (PrimKind from : INTS) {
            for (PrimKind to : INTS) {
                for (int i = 0; i < 2_000; i++) {
                    long a = sample(from);
                    BigInteger v = value(from, a);
                    if (to.fits(v)) {
                        assertEquals(v, value(to, IntOps.convertInt(from, to, a, null)), from.name + " -> " + to.name + " " + v);
                    } else {
                        Trap t = assertThrows(Trap.class, () -> IntOps.convertInt(from, to, a, null), from.name + " -> " + to.name + " " + v);
                        assertEquals(Code.R0009, t.code);
                        assertTrue(t.getMessage().contains(v.toString()), t.getMessage());
                    }
                }
            }
        }
    }

    @Test
    void integers_convert_to_the_nearest_float() {
        for (int i = 0; i < 50_000; i++) {
            long a = next();
            // BigDecimal.doubleValue is correctly rounded, half to even: the reference for every u64.
            java.math.BigDecimal exact = new java.math.BigDecimal(IntOps.toBig(PrimKind.U64, a));
            assertEquals(exact.doubleValue(), IntOps.toDouble(PrimKind.U64, a), Long.toUnsignedString(a));
            assertEquals(exact.floatValue(), IntOps.toFloat(PrimKind.U64, a), Long.toUnsignedString(a));
            assertEquals((double) a, IntOps.toDouble(PrimKind.I64, a));
        }
        assertEquals(1.8446744073709551616E19, IntOps.toDouble(PrimKind.U64, -1));
    }

    @Test
    void floats_truncate_toward_zero_and_trap_out_of_range() {
        assertEquals(-2, IntOps.fromDouble(-2.9, PrimKind.I64, null));
        assertEquals(2, IntOps.fromDouble(2.9, PrimKind.I8, null));
        assertEquals(0, IntOps.fromDouble(-0.5, PrimKind.U8, null));
        assertEquals(255, IntOps.fromDouble(255.99, PrimKind.U8, null));
        assertEquals(Long.MIN_VALUE, IntOps.fromDouble(-9.223372036854775808E18, PrimKind.I64, null));
        assertEquals(Long.MIN_VALUE, IntOps.fromDouble(9.223372036854775808E18, PrimKind.U64, null));
        assertEquals("18446744073709549568", Long.toUnsignedString(IntOps.fromDouble(Math.nextDown(1.8446744073709551616E19), PrimKind.U64, null)));
        double[] bad = {Double.NaN, 256.0, -1.0, 9.223372036854775808E18, 1.8446744073709551616E19, Double.POSITIVE_INFINITY};
        PrimKind[] kinds = {PrimKind.I32, PrimKind.U8, PrimKind.U16, PrimKind.I64, PrimKind.U64, PrimKind.U64};
        for (int i = 0; i < bad.length; i++) {
            double d = bad[i];
            PrimKind k = kinds[i];
            Trap t = assertThrows(Trap.class, () -> IntOps.fromDouble(d, k, null), d + " -> " + k.name);
            assertEquals(Code.R0009, t.code);
        }
        assertTrue(assertThrows(Trap.class, () -> IntOps.fromDouble(Double.NaN, PrimKind.I8, null)).getMessage().contains("NaN"));
    }
}
