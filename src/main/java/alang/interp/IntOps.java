package alang.interp;

import alang.diag.Code;
import alang.diag.Span;
import alang.types.PrimKind;
import java.math.BigInteger;

/**
 * Checked integer arithmetic for every integer width. Values are carried in a long:
 * signed kinds sign-extended, u8..u32 zero-extended, u64 as its raw bit pattern.
 */
final class IntOps {
    private IntOps() {}

    private static final double TWO_63 = 9.223372036854775808E18;
    private static final double TWO_64 = 1.8446744073709551616E19;

    static Trap overflow(Span at, PrimKind k, String op) {
        return new Trap(Code.R0001, at, "integer overflow: result of `" + op + "` does not fit in " + k.name);
    }

    private static long min(PrimKind k) {
        return k.signed ? -(1L << (k.bits - 1)) : 0;
    }

    private static long max(PrimKind k) {
        return k.signed ? (1L << (k.bits - 1)) - 1 : (1L << k.bits) - 1;
    }

    private static long range(PrimKind k, long r, Span at, String op) {
        if (r < min(k) || r > max(k)) throw overflow(at, k, op);
        return r;
    }

    static long add(PrimKind k, long a, long b, Span at) {
        long r = a + b;
        boolean bad = switch (k) {
            case I64 -> ((a ^ r) & (b ^ r)) < 0;
            case U64 -> Long.compareUnsigned(r, a) < 0;
            default -> r < min(k) || r > max(k);
        };
        if (bad) throw overflow(at, k, "+");
        return r;
    }

    static long sub(PrimKind k, long a, long b, Span at) {
        long r = a - b;
        boolean bad = switch (k) {
            case I64 -> ((a ^ b) & (a ^ r)) < 0;
            case U64 -> Long.compareUnsigned(a, b) < 0;
            default -> r < min(k) || r > max(k);
        };
        if (bad) throw overflow(at, k, "-");
        return r;
    }

    static long mul(PrimKind k, long a, long b, Span at) {
        long r = a * b;
        boolean bad = switch (k) {
            case I64 -> {
                long hi = Math.multiplyHigh(a, b);
                yield (hi != 0 || r < 0) && (hi != -1 || r >= 0);
            }
            case U64 -> Math.unsignedMultiplyHigh(a, b) != 0;
            default -> r < min(k) || r > max(k);
        };
        if (bad) throw overflow(at, k, "*");
        return r;
    }

    static long div(PrimKind k, long a, long b, Span at) {
        if (b == 0) throw new Trap(Code.R0002, at, "division by zero");
        if (k == PrimKind.U64) return Long.divideUnsigned(a, b);
        // range() never fails for i64, whose only overflow is MIN / -1.
        if (k == PrimKind.I64 && a == Long.MIN_VALUE && b == -1) throw overflow(at, k, "/");
        return range(k, a / b, at, "/");
    }

    static long rem(PrimKind k, long a, long b, Span at) {
        if (b == 0) throw new Trap(Code.R0002, at, "remainder by zero");
        if (k == PrimKind.U64) return Long.remainderUnsigned(a, b);
        if (b == -1) return 0;
        return a % b;
    }

    static long neg(PrimKind k, long a, Span at) {
        if (k == PrimKind.I64 && a == Long.MIN_VALUE) throw overflow(at, k, "-");
        return range(k, -a, at, "-");
    }

    /** |a| for a signed kind. Only the minimum has no absolute value; its trap names `abs`, not `-`. */
    static long abs(PrimKind k, long a, Span at) {
        if (a == min(k)) throw overflow(at, k, "abs");
        return a < 0 ? -a : a;
    }

    static long bnot(PrimKind k, long a) {
        if (k.signed || k == PrimKind.U64) return ~a;
        return ~a & max(k);
    }

    private static long truncate(PrimKind k, long r) {
        if (k.bits == 64) return r;
        if (k.signed) return (r << (64 - k.bits)) >> (64 - k.bits);
        return r & max(k);
    }

    private static int shiftAmount(PrimKind k, PrimKind amountKind, long n, Span at) {
        boolean negative = amountKind != PrimKind.U64 && n < 0;
        if (negative || Long.compareUnsigned(n, k.bits) >= 0) {
            throw new Trap(Code.R0012, at, "shift amount " + (amountKind == PrimKind.U64 ? Long.toUnsignedString(n) : n)
                + " is out of range for " + k.name + " (must be 0.." + (k.bits - 1) + ")");
        }
        return (int) n;
    }

    static long shl(PrimKind k, PrimKind amountKind, long a, long n, Span at) {
        return truncate(k, a << shiftAmount(k, amountKind, n, at));
    }

    static long shr(PrimKind k, PrimKind amountKind, long a, long n, Span at) {
        int s = shiftAmount(k, amountKind, n, at);
        return k.signed ? a >> s : a >>> s;
    }

    static int compare(PrimKind k, long a, long b) {
        return k == PrimKind.U64 ? Long.compareUnsigned(a, b) : Long.compare(a, b);
    }

    static String toString(PrimKind k, long v) {
        return k == PrimKind.U64 ? Long.toUnsignedString(v) : Long.toString(v);
    }

    static BigInteger toBig(PrimKind k, long v) {
        return k == PrimKind.U64 ? new BigInteger(Long.toUnsignedString(v)) : BigInteger.valueOf(v);
    }

    /** Integer to integer conversion with a range check. */
    static long convertInt(PrimKind from, PrimKind to, long v, Span at) {
        boolean ok;
        if (from == PrimKind.U64 && v < 0) {
            ok = to == PrimKind.U64;
        } else if (to == PrimKind.U64) {
            ok = v >= 0;
        } else {
            ok = v >= min(to) && v <= max(to);
        }
        if (!ok) {
            throw new Trap(Code.R0009, at, "value " + toString(from, v) + " does not fit in " + to.name);
        }
        return v;
    }

    /*
     * u64 values >= 2^63 are halved with a sticky low bit, converted (correctly rounded,
     * round-to-nearest-even), then doubled exactly. This is the same sequence C compilers
     * emit for (double)uint64_t, so both backends round identically.
     */
    static double toDouble(PrimKind from, long v) {
        if (from == PrimKind.U64 && v < 0) return (double) ((v >>> 1) | (v & 1)) * 2.0;
        return (double) v;
    }

    static float toFloat(PrimKind from, long v) {
        if (from == PrimKind.U64 && v < 0) return (float) ((v >>> 1) | (v & 1)) * 2.0f;
        return (float) v;
    }

    /** Float to integer conversion: truncates toward zero; traps on NaN or out of range. */
    static long fromDouble(double d, PrimKind to, Span at) {
        if (Double.isNaN(d)) throw new Trap(Code.R0009, at, "cannot convert NaN to " + to.name);
        double t = d < 0 ? Math.ceil(d) : Math.floor(d);
        boolean ok;
        if (to == PrimKind.I64) ok = t >= -TWO_63 && t < TWO_63;
        else if (to == PrimKind.U64) ok = t >= 0 && t < TWO_64;
        else ok = t >= min(to) && t <= max(to);
        if (!ok) throw new Trap(Code.R0009, at, "value " + Format.formatFloat(d) + " does not fit in " + to.name);
        if (to == PrimKind.U64 && t >= TWO_63) return (long) (t - TWO_63) + Long.MIN_VALUE;
        return (long) t;
    }
}
