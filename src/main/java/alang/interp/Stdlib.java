package alang.interp;

import alang.ast.Ast.Variant;
import alang.ast.Builtin;
import alang.diag.Code;
import alang.diag.Span;
import alang.interp.Values.ArrV;
import alang.interp.Values.EnumV;
import alang.interp.Values.ListV;
import alang.interp.Values.MapV;
import alang.interp.Values.StrV;
import alang.interp.Values.StructV;
import alang.types.PrimKind;
import alang.types.Type;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The standard library's functions in the reference interpreter. The C backend's prelude and generated
 * helpers implement each one the same way, with the same traps and messages; the order in which a
 * function checks its arguments is part of its behavior, since it decides which trap comes first.
 */
final class Stdlib {
    private Stdlib() {}

    // =================================================================== math

    /** x to the power n, checked: R0013 if n is negative, R0001 if the result does not fit x's type. */
    static long pow(PrimKind k, long x, PrimKind nk, long n, Span at) {
        if (nk.signed && n < 0) throw new Trap(Code.R0013, at, "pow exponent " + n + " is negative");
        // |x| <= 1 needs no loop; otherwise every step at least doubles |r|, so the loop overflows within 64
        // steps, and since |r| only grows, a step overflows exactly when the result would.
        if (x == 0) return n == 0 ? 1 : 0;
        if (x == 1) return 1;
        if (k.signed && x == -1) return (n & 1) == 0 ? 1 : -1;
        BigInteger r = BigInteger.ONE;
        BigInteger bx = IntOps.toBig(k, x);
        long left = n;
        while (left != 0) {
            r = r.multiply(bx);
            if (!k.fits(r)) throw IntOps.overflow(at, k, "pow");
            left--;
        }
        return r.longValue();
    }

    /** The magnitude of an integer as an unsigned 64-bit pattern (|MIN| of i64 is 2^63). */
    private static long magnitude(PrimKind k, long v) {
        return k.signed && v < 0 ? -v : v;
    }

    private static long gcdUnsigned(long a, long b) {
        while (b != 0) {
            long t = Long.remainderUnsigned(a, b);
            a = b;
            b = t;
        }
        return a;
    }

    /** An unsigned 64-bit result as a value of k, or R0001 naming {@code op}. */
    private static long fit(PrimKind k, long u, Span at, String op) {
        boolean ok = k == PrimKind.U64 || (u >= 0 && BigInteger.valueOf(u).compareTo(k.max()) <= 0);
        if (!ok) throw IntOps.overflow(at, k, op);
        return u;
    }

    static long gcd(PrimKind k, long a, long b, Span at) {
        return fit(k, gcdUnsigned(magnitude(k, a), magnitude(k, b)), at, "gcd");
    }

    static long lcm(PrimKind k, long a, long b, Span at) {
        if (a == 0 || b == 0) return 0;
        long ma = magnitude(k, a);
        long mb = magnitude(k, b);
        long q = Long.divideUnsigned(ma, gcdUnsigned(ma, mb));
        // q * mb must fit in 64 unsigned bits before it is checked against k.
        if (Long.compareUnsigned(mb, Long.divideUnsigned(-1L, q)) > 0) throw IntOps.overflow(at, k, "lcm");
        return fit(k, q * mb, at, "lcm");
    }

    /**
     * wrapping_add/sub/mul and saturating_add/sub/mul: the exact result, then either its low k.bits bits (as a
     * value of k) or the nearest value of k. Neither traps.
     */
    static long wrapOrSaturate(Builtin b, PrimKind k, long a, long y) {
        BigInteger x = IntOps.toBig(k, a);
        BigInteger z = IntOps.toBig(k, y);
        BigInteger r = switch (b) {
            case WRAPPING_ADD, SATURATING_ADD -> x.add(z);
            case WRAPPING_SUB, SATURATING_SUB -> x.subtract(z);
            default -> x.multiply(z);
        };
        boolean wrap = b == Builtin.WRAPPING_ADD || b == Builtin.WRAPPING_SUB || b == Builtin.WRAPPING_MUL;
        if (wrap) {
            long low = r.longValue();
            if (k.bits == 64) return low;
            return k.signed ? (low << (64 - k.bits)) >> (64 - k.bits) : low & ((1L << k.bits) - 1);
        }
        if (r.compareTo(k.max()) > 0) r = k.max();
        if (r.compareTo(k.min()) < 0) r = k.min();
        return r.longValue();
    }

    static Object clamp(Type t, Object x, Object lo, Object hi, Span at) {
        PrimKind k = Type.kind(t);
        if (k.isFloat) {
            double dx = (Double) x;
            double dlo = (Double) lo;
            double dhi = (Double) hi;
            if (!(dlo <= dhi)) throw clampTrap(t, lo, hi, at);
            return dx < dlo ? lo : dx > dhi ? hi : x;
        }
        long lx = (Long) x;
        if (IntOps.compare(k, (Long) lo, (Long) hi) > 0) throw clampTrap(t, lo, hi, at);
        if (IntOps.compare(k, lx, (Long) lo) < 0) return lo;
        if (IntOps.compare(k, lx, (Long) hi) > 0) return hi;
        return x;
    }

    private static Trap clampTrap(Type t, Object lo, Object hi, Span at) {
        return new Trap(Code.R0013, at, "clamp needs lo <= hi, found lo " + text(lo, t) + " and hi " + text(hi, t));
    }

    /** Halves away from zero, as C's round(); exact, since d - trunc(d) and trunc(d) +- 1 are. */
    static double round(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return d;
        double t = trunc(d);
        double frac = d - t;
        if (frac >= 0.5) return t + 1.0;
        if (frac <= -0.5) return t - 1.0;
        return t;
    }

    static double trunc(double d) {
        return d < 0 ? Math.ceil(d) : Math.floor(d);
    }

    // =================================================================== characters

    static boolean isDigit(long c) {
        return c >= '0' && c <= '9';
    }

    static boolean isUpper(long c) {
        return c >= 'A' && c <= 'Z';
    }

    static boolean isLower(long c) {
        return c >= 'a' && c <= 'z';
    }

    static boolean isAlpha(long c) {
        return isUpper(c) || isLower(c);
    }

    static boolean isSpace(long c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    static long toUpper(long c) {
        return isLower(c) ? c - 32 : c;
    }

    static long toLower(long c) {
        return isUpper(c) ? c + 32 : c;
    }

    static StrV mapCase(StrV s, boolean upper, Meter cost) {
        cost.bytes((long) s.b.length + s.len);
        StrV r = new StrV(s.b.clone(), s.len);
        for (int i = 0; i < r.len; i++) {
            long c = r.b[i] & 0xFF;
            r.b[i] = (byte) (upper ? toUpper(c) : toLower(c));
        }
        return r;
    }

    // =================================================================== text

    /** What a text function looks for: the bytes of a str, or a single u8 byte. */
    static byte[] pattern(Object v, Meter cost) {
        if (!(v instanceof StrV s)) return new byte[] {(byte) (long) (Long) v};
        cost.bytes(s.len);
        return Arrays.copyOf(s.b, s.len);
    }

    /**
     * The first position at or after {@code from} where {@code n} occurs in the first {@code hn} bytes of h, or -1.
     * It costs an element, a byte for each position it looks at, and an element and the bytes that match for
     * each position that starts like n: a pattern that almost matches everywhere costs its length at every position.
     */
    static int find(byte[] h, int hn, byte[] n, int from, Meter cost) {
        cost.elements(1);
        if (n.length == 0) return from <= hn ? from : -1;
        byte first = n[0];
        int last = hn - n.length;
        int i = from;
        // The candidates' cost is passed on in batches, often enough that one search cannot outrun the budget.
        long pending = 0;
        for (; i <= last; i++) {
            if (h[i] != first) continue;
            int k = Arrays.mismatch(h, i + 1, i + n.length, n, 1, n.length);
            pending += Meter.ELEMENT + (k < 0 ? n.length - 1 : k + 1);
            if (k < 0) {
                cost.units(pending + (i - from + 1L));
                return i;
            }
            if (pending >= FIND_BATCH) {
                cost.units(pending);
                pending = 0;
            }
        }
        cost.units(pending + Math.max(0, i - from));
        return -1;
    }

    /** The units a search gathers before it charges them: a few thousand steps' worth of work. */
    private static final long FIND_BATCH = 1 << 18;

    static boolean startsWith(StrV s, byte[] p, Meter cost) {
        cost.bytes(p.length);
        return p.length <= s.len && Arrays.equals(s.b, 0, p.length, p, 0, p.length);
    }

    static boolean endsWith(StrV s, byte[] p, Meter cost) {
        cost.bytes(p.length);
        return p.length <= s.len && Arrays.equals(s.b, s.len - p.length, s.len, p, 0, p.length);
    }

    /** The position of the first element of a list or array equal to x, or -1. */
    static long indexOf(Object xs, Object x, Type elem, Meter cost) {
        Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
        int n = xs instanceof ListV l ? l.len : items.length;
        for (int i = 0; i < n; i++) {
            cost.elements(1);
            if (Values.equal(items[i], x, elem, cost)) return i;
        }
        return -1;
    }

    /** The non-overlapping occurrences of p in s, left to right; R0013 if p is empty. */
    static long count(StrV s, byte[] p, Span at, Meter cost) {
        if (p.length == 0) throw new Trap(Code.R0013, at, "count pattern is empty");
        long n = 0;
        for (int hit = find(s.b, s.len, p, 0, cost); hit >= 0; hit = find(s.b, s.len, p, hit + p.length, cost)) n++;
        return n;
    }

    /** The elements of a list or array equal to x. */
    static long count(Object xs, Object x, Type elem, Meter cost) {
        Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
        int n = xs instanceof ListV l ? l.len : items.length;
        long k = 0;
        for (int i = 0; i < n; i++) {
            cost.elements(1);
            if (Values.equal(items[i], x, elem, cost)) k++;
        }
        return k;
    }

    static StrV trim(StrV s, Meter cost) {
        cost.bytes((long) s.b.length + s.len);
        int start = 0;
        int end = s.len;
        while (start < end && isSpace(s.b[start] & 0xFF)) start++;
        while (end > start && isSpace(s.b[end - 1] & 0xFF)) end--;
        byte[] b = new byte[s.b.length];
        System.arraycopy(s.b, start, b, 0, end - start);
        return new StrV(b, end - start);
    }

    /** Whether a position is within 0..len; a u64 above 2^63 is a negative long, and so is not. */
    private static boolean within(long v, int len) {
        return v >= 0 && v <= len;
    }

    /** A copy of xs[from..to] of type {@code t}: the type of xs, or a list for an array. */
    static Object slice(Object xs, Type t, long from, PrimKind fk, long to, PrimKind tk, Span at, Meter cost) {
        int len = switch (xs) {
            case StrV s -> s.len;
            case ListV l -> l.len;
            default -> ((ArrV) xs).items.length;
        };
        if (!within(from, len) || !within(to, len) || from > to) {
            throw new Trap(Code.R0003, at, "slice " + IntOps.toString(fk, from) + ".." + IntOps.toString(tk, to)
                + " is out of range for length " + len);
        }
        int f = (int) from;
        int n = (int) (to - from);
        if (xs instanceof StrV s) {
            cost.bytes(s.b.length + (long) n);
            byte[] b = new byte[s.b.length];
            System.arraycopy(s.b, f, b, 0, n);
            return new StrV(b, n);
        }
        Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
        Object[] out = new Object[((Type.ListT) t).cap()];
        cost.slots(out.length);
        cost.elements(n);
        for (int i = 0; i < n; i++) out[i] = Values.copy(items[f + i], cost);
        return new ListV(out, n);
    }

    /**
     * The pieces of s between occurrences of sep, or (sep null) between runs of whitespace, as a value of
     * type t. Each piece is checked in turn: first that the list has room for it, then that it fits.
     */
    static ListV split(String name, StrV s, byte[] sep, Type.ListT t, Span at, Meter cost) {
        cost.slots(t.cap());
        ListV r = new ListV(new Object[t.cap()], 0);
        fill(name, r, s, sep, t, false, at, cost);
        return r;
    }

    /**
     * try_split and try_words: the pieces of {@code s} into the list {@code r} of type {@code t}, replacing what
     * it held. Returns false, and leaves the list empty, when they do not fit.
     */
    static boolean trySplit(ListV r, StrV s, byte[] sep, Type.ListT t, Span at, Meter cost) {
        return fill(sep != null ? "split" : "words", r, s, sep, t, true, at, cost);
    }

    /** split (a null {@code sep}: words) into {@code r}; with {@code tryOnly}, false instead of a trap when it does not fit. */
    private static boolean fill(String name, ListV r, StrV s, byte[] sep, Type.ListT t, boolean tryOnly, Span at, Meter cost) {
        int k = ((Type.Str) t.elem()).cap();
        r.len = 0;
        if (sep != null) {
            if (sep.length == 0) throw new Trap(Code.R0013, at, "split separator is empty");
            int pos = 0;
            while (true) {
                int hit = find(s.b, s.len, sep, pos, cost);
                int end = hit < 0 ? s.len : hit;
                if (!piece(name, r, s, pos, end, k, t, tryOnly, at, cost)) return false;
                if (hit < 0) break;
                pos = end + sep.length;
            }
        } else {
            int i = 0;
            while (true) {
                int from = i;
                while (i < s.len && isSpace(s.b[i] & 0xFF)) i++;
                if (i == s.len) {
                    cost.bytes(i - from);
                    break;
                }
                int start = i;
                while (i < s.len && !isSpace(s.b[i] & 0xFF)) i++;
                cost.bytes(i - from);
                if (!piece(name, r, s, start, i, k, t, tryOnly, at, cost)) return false;
            }
        }
        return true;
    }

    private static boolean piece(String name, ListV r, StrV s, int from, int to, int k, Type.ListT t, boolean tryOnly, Span at,
            Meter cost) {
        if (tryOnly && (r.len == r.items.length || to - from > k)) {
            r.len = 0;
            return false;
        }
        if (r.len == r.items.length) {
            throw new Trap(Code.R0004, at, name + " makes more than " + r.items.length + " pieces, the capacity of " + t);
        }
        if (to - from > k) {
            throw new Trap(Code.R0004, at, name + " piece of " + (to - from) + " bytes exceeds str capacity " + k);
        }
        cost.units(Meter.ELEMENT + k);
        byte[] b = new byte[k];
        System.arraycopy(s.b, from, b, 0, to - from);
        r.items[r.len++] = new StrV(b, to - from);
        return true;
    }

    static StrV join(Object parts, byte[] sep, Type.Str t, Span at, Meter cost) {
        Object[] items = parts instanceof ListV l ? l.items : ((ArrV) parts).items;
        int n = parts instanceof ListV l ? l.len : items.length;
        cost.elements(n);
        long need = 0;
        for (int i = 0; i < n; i++) need += ((StrV) items[i]).len + (i > 0 ? sep.length : 0);
        if (need > t.cap()) {
            throw new Trap(Code.R0004, at, "join needs " + need + " bytes, more than str capacity " + t.cap());
        }
        cost.bytes(t.cap() + need);
        StrV r = new StrV(new byte[t.cap()], 0);
        for (int i = 0; i < n; i++) {
            if (i > 0) put(r, sep, 0, sep.length);
            StrV p = (StrV) items[i];
            put(r, p.b, 0, p.len);
        }
        return r;
    }

    private static void put(StrV r, byte[] b, int from, int n) {
        System.arraycopy(b, from, r.b, r.len, n);
        r.len += n;
    }

    static StrV replace(StrV s, byte[] from, byte[] to, Type.Str t, Span at, Meter cost) {
        if (from.length == 0) throw new Trap(Code.R0013, at, "replace pattern is empty");
        long need = s.len;
        for (int hit = find(s.b, s.len, from, 0, cost); hit >= 0; hit = find(s.b, s.len, from, hit + from.length, cost)) {
            need += to.length - from.length;
        }
        if (need > t.cap()) {
            throw new Trap(Code.R0004, at, "replace needs " + need + " bytes, more than str capacity " + t.cap());
        }
        cost.bytes(t.cap() + need);
        StrV r = new StrV(new byte[t.cap()], 0);
        int pos = 0;
        for (int hit = find(s.b, s.len, from, 0, cost); hit >= 0; hit = find(s.b, s.len, from, pos, cost)) {
            put(r, s.b, pos, hit - pos);
            put(r, to, 0, to.length);
            pos = hit + from.length;
        }
        put(r, s.b, pos, s.len - pos);
        return r;
    }

    /**
     * The value of decimal text: an optional + or -, then at least one digit, nothing else. Null if the
     * text has another form. More than 20 digits after the leading zeros fit no integer type, so the value
     * is then +-2^64 (which fits none either) instead of a number that takes time to build.
     */
    private static BigInteger decimal(StrV s, Meter cost) {
        cost.bytes(s.len);
        int i = 0;
        if (s.len > 0 && (s.b[0] == '+' || s.b[0] == '-')) i = 1;
        if (i == s.len) return null;
        for (int j = i; j < s.len; j++) {
            if (!isDigit(s.b[j])) return null;
        }
        while (i < s.len - 1 && s.b[i] == '0') i++;
        BigInteger v = s.len - i > 20 ? BEYOND_64_BITS : new BigInteger(new String(s.b, i, s.len - i, StandardCharsets.US_ASCII));
        return s.b[0] == '-' ? v.negate() : v;
    }

    /** 2^64: neither it nor its negation fits a 64-bit integer type. */
    private static final BigInteger BEYOND_64_BITS = BigInteger.ONE.shiftLeft(64);

    static boolean isInt(StrV s, Meter cost) {
        BigInteger v = decimal(s, cost);
        return v != null && PrimKind.I64.fits(v);
    }

    /** The conversion of text to an integer type: R0009 unless it is decimal text that fits. */
    static long parseInt(StrV s, PrimKind to, Span at, Meter cost) {
        BigInteger v = decimal(s, cost);
        if (v == null || !to.fits(v)) {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            msg.writeBytes("cannot convert ".getBytes(StandardCharsets.UTF_8));
            Format.format(ByteSink.of(msg), s, new Type.Str(s.b.length), true);
            msg.writeBytes((" to " + to.name + (v == null ? ": not a decimal integer" : ": out of range"))
                .getBytes(StandardCharsets.UTF_8));
            throw new Trap(Code.R0009, at, msg.toByteArray());
        }
        return v.longValue();
    }

    /** 2^53: every integer up to it is an f64. */
    private static final long EXACT_MAX = 1L << 53;

    /** The powers of ten that are f64s: 1e0 to 1e22. */
    private static final double[] POW10 = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18,
        1e19, 1e20, 1e21, 1e22};

    /** What {@link #decimalFloat} found: no decimal number, one too precise to convert exactly, or its value. */
    private static final int NOT_DECIMAL = 0;
    private static final int TOO_PRECISE = 1;
    private static final int EXACT = 2;

    /**
     * Decimal text as an f64: an optional + or -, then digits with at most one `.` among them. It is
     * M * 10^E with M the digits between the first and last that are not 0; the text converts when M is at
     * most 2^53 and 10^E (or M * 10^(E - 22) and 10^22) is an f64 too, so that one multiplication or division
     * rounds the exact value to the nearest f64. The C backend's al_parse_f64 is the same algorithm. Returns
     * the kind, and the value in {@code out[0]}.
     */
    private static int decimalFloat(StrV s, double[] out, Meter cost) {
        cost.bytes(2L * s.len);
        int i = 0;
        boolean negative = s.len > 0 && s.b[0] == '-';
        if (s.len > 0 && (s.b[0] == '+' || s.b[0] == '-')) i = 1;
        int digits = 0;
        int beforePoint = -1;
        int first = -1;
        int last = -1;
        for (int j = i; j < s.len; j++) {
            byte c = s.b[j];
            if (c == '.') {
                if (beforePoint >= 0) return NOT_DECIMAL;
                beforePoint = digits;
            } else if (isDigit(c)) {
                if (c != '0') {
                    if (first < 0) first = digits;
                    last = digits;
                }
                digits++;
            } else {
                return NOT_DECIMAL;
            }
        }
        if (digits == 0) return NOT_DECIMAL;
        if (beforePoint < 0) beforePoint = digits;
        if (first < 0) {
            out[0] = negative ? -0.0 : 0.0;
            return EXACT;
        }
        if (last - first >= 16) return TOO_PRECISE;
        long m = 0;
        int k = 0;
        for (int j = i; j < s.len; j++) {
            if (s.b[j] == '.') continue;
            if (k >= first && k <= last) m = m * 10 + (s.b[j] - '0');
            k++;
        }
        if (m > EXACT_MAX) return TOO_PRECISE;
        // The value is m * 10^e.
        long e = (long) beforePoint - 1 - last;
        double v;
        if (e < 0) {
            if (e < -22) return TOO_PRECISE;
            v = (double) m / POW10[(int) -e];
        } else if (e <= 22) {
            v = (double) m * POW10[(int) e];
        } else {
            if (e > 22 + 16) return TOO_PRECISE;
            for (long n = e - 22; n > 0; n--) {
                m *= 10;
                if (m > EXACT_MAX) return TOO_PRECISE;
            }
            v = (double) m * POW10[22];
        }
        out[0] = negative ? -v : v;
        return EXACT;
    }

    static boolean isFloat(StrV s, Meter cost) {
        return decimalFloat(s, new double[1], cost) == EXACT;
    }

    /** The conversion of text to f64: R0009 unless {@link #isFloat}. */
    static double parseFloat(StrV s, Span at, Meter cost) {
        double[] v = new double[1];
        int kind = decimalFloat(s, v, cost);
        if (kind != EXACT) {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            msg.writeBytes("cannot convert ".getBytes(StandardCharsets.UTF_8));
            Format.format(ByteSink.of(msg), s, new Type.Str(s.b.length), true);
            msg.writeBytes((" to f64" + (kind == NOT_DECIMAL ? ": not a decimal number"
                : ": more digits than an f64 converts exactly")).getBytes(StandardCharsets.UTF_8));
            throw new Trap(Code.R0009, at, msg.toByteArray());
        }
        return v[0];
    }

    // =================================================================== UTF-8

    /**
     * The code point that starts at byte i of b[0..n), as (code point << 8) | (valid ? 0x80 : 0) | length. A
     * byte that does not start a well-formed sequence (overlong, a surrogate, past U+10FFFF, or cut short)
     * stands for U+FFFD on its own, so that every byte belongs to exactly one code point.
     */
    static long utf8Next(byte[] b, int n, int i) {
        int b0 = b[i] & 0xFF;
        if (b0 < 0x80) return ((long) b0 << 8) | 0x80 | 1;
        int len;
        int cp;
        int lo = 0x80;
        int hi = 0xBF;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            len = 2;
            cp = b0 & 0x1F;
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            len = 3;
            cp = b0 & 0x0F;
            if (b0 == 0xE0) lo = 0xA0;
            if (b0 == 0xED) hi = 0x9F;
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            len = 4;
            cp = b0 & 0x07;
            if (b0 == 0xF0) lo = 0x90;
            if (b0 == 0xF4) hi = 0x8F;
        } else {
            return (0xFFFDL << 8) | 1;
        }
        if (n - i < len) return (0xFFFDL << 8) | 1;
        for (int k = 1; k < len; k++) {
            int c = b[i + k] & 0xFF;
            if (c < (k == 1 ? lo : 0x80) || c > (k == 1 ? hi : 0xBF)) return (0xFFFDL << 8) | 1;
            cp = (cp << 6) | (c & 0x3F);
        }
        return ((long) cp << 8) | 0x80 | len;
    }

    static boolean isUtf8(StrV s, Meter cost) {
        cost.bytes(s.len);
        for (int i = 0; i < s.len; ) {
            long d = utf8Next(s.b, s.len, i);
            if ((d & 0x80) == 0) return false;
            i += (int) (d & 7);
        }
        return true;
    }

    static long charCount(StrV s, Meter cost) {
        cost.bytes(s.len);
        long count = 0;
        for (int i = 0; i < s.len; i += (int) (utf8Next(s.b, s.len, i) & 7)) count++;
        return count;
    }

    /** The code points of s as a list of type t; R0004 if there are more than it holds. */
    static ListV chars(StrV s, Type.ListT t, Span at, Meter cost) {
        cost.slots(t.cap());
        cost.bytes(s.len);
        ListV r = new ListV(new Object[t.cap()], 0);
        for (int i = 0; i < s.len; ) {
            long d = utf8Next(s.b, s.len, i);
            if (r.len == t.cap()) {
                throw new Trap(Code.R0004, at, "chars makes more than " + t.cap() + " code points, the capacity of " + t);
            }
            r.items[r.len++] = d >>> 8;
            i += (int) (d & 7);
        }
        cost.elements(r.len);
        return r;
    }

    /** Appends the UTF-8 encoding of the code point c (of integer type k) to s. */
    static void pushChar(StrV s, long c, PrimKind k, Span at) {
        boolean scalar = !(k.signed && c < 0) && Long.compareUnsigned(c, 0x10FFFF) <= 0 && (c < 0xD800 || c > 0xDFFF);
        if (!scalar) {
            throw new Trap(Code.R0013, at, "push_char needs a Unicode code point from 0 to 1114111 that is not a surrogate "
                + "(55296 to 57343), found " + IntOps.toString(k, c));
        }
        int n = c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
        if (s.len + n > s.b.length) {
            throw new Trap(Code.R0004, at, "push_char exceeds str capacity " + s.b.length + " (length " + s.len + ", appending "
                + n + " bytes)");
        }
        int v = (int) c;
        if (n == 1) {
            s.b[s.len++] = (byte) v;
            return;
        }
        int lead = n == 2 ? 0xC0 : n == 3 ? 0xE0 : 0xF0;
        s.b[s.len++] = (byte) (lead | (v >>> (6 * (n - 1))));
        for (int k2 = n - 2; k2 >= 0; k2--) s.b[s.len++] = (byte) (0x80 | ((v >>> (6 * k2)) & 0x3F));
    }

    /** The code points from..to of s, as a str of the same capacity; R0003 unless 0 <= from <= to <= char_count(s). */
    static StrV sliceChars(StrV s, long from, PrimKind fk, long to, PrimKind tk, Span at, Meter cost) {
        long count = charCount(s, cost);
        if (from < 0 || from > count || to < 0 || to > count || from > to) {
            throw new Trap(Code.R0003, at, "slice_chars " + IntOps.toString(fk, from) + ".." + IntOps.toString(tk, to)
                + " is out of range for " + count + " code points");
        }
        int i = 0;
        int start = 0;
        for (long k = 0; k < to; k++) {
            if (k == from) start = i;
            i += (int) (utf8Next(s.b, s.len, i) & 7);
        }
        if (from == to) start = i;
        cost.bytes(s.b.length + (long) i);
        byte[] b = new byte[s.b.length];
        System.arraycopy(s.b, start, b, 0, i - start);
        return new StrV(b, i - start);
    }

    // =================================================================== collections

    /**
     * The order sort uses, defined for every type: numbers by value (-0.0 before 0.0, every NaN after
     * everything else and equal to the others), false before true, strs, arrays and lists element by element
     * (a prefix first), structs field by field, enums by variant and then payload. Values it calls equal
     * cannot be told apart, so every correct sorting algorithm gives the same result. It costs what it compares,
     * as {@link Values#equal} does.
     */
    static int compare(Object a, Object b, Type t, Meter cost) {
        return switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                if (k == PrimKind.BOOL) yield Boolean.compare((Boolean) a, (Boolean) b);
                if (k.isFloat) yield Double.compare((Double) a, (Double) b);
                yield Integer.signum(IntOps.compare(k, (Long) a, (Long) b));
            }
            case Type.Str s -> {
                StrV x = (StrV) a;
                StrV y = (StrV) b;
                int k = Arrays.mismatch(x.b, 0, x.len, y.b, 0, y.len);
                if (k < 0) {
                    cost.bytes(x.len);
                    yield 0;
                }
                cost.bytes(k + 1L);
                if (k == x.len || k == y.len) yield Integer.compare(x.len, y.len);
                yield Integer.compare(x.b[k] & 0xFF, y.b[k] & 0xFF);
            }
            case Type.ArrayT at -> compareAll(((ArrV) a).items, ((ArrV) b).items, at.len(), at.len(), at.elem(), cost);
            case Type.ListT l -> compareAll(((ListV) a).items, ((ListV) b).items, ((ListV) a).len, ((ListV) b).len, l.elem(), cost);
            case Type.StructT st -> {
                Object[] x = ((StructV) a).fields;
                Object[] y = ((StructV) b).fields;
                for (int i = 0; i < x.length; i++) {
                    cost.elements(1);
                    int c = compare(x[i], y[i], st.decl().fields.get(i).type, cost);
                    if (c != 0) yield c;
                }
                yield 0;
            }
            case Type.SetT st -> compareAll(((ListV) a).items, ((ListV) b).items, ((ListV) a).len, ((ListV) b).len, st.elem(), cost);
            case Type.MapT mt -> {
                // Entry by entry, key then value; a map that is a prefix of the other first.
                MapV x = (MapV) a;
                MapV y = (MapV) b;
                for (int i = 0; i < x.len && i < y.len; i++) {
                    cost.elements(1);
                    int c = compare(x.keys[i], y.keys[i], mt.key(), cost);
                    if (c == 0) c = compare(x.vals[i], y.vals[i], mt.value(), cost);
                    if (c != 0) yield c;
                }
                yield Integer.compare(x.len, y.len);
            }
            case Type.TupleT tt -> {
                Object[] x = ((StructV) a).fields;
                Object[] y = ((StructV) b).fields;
                for (int i = 0; i < x.length; i++) {
                    cost.elements(1);
                    int c = compare(x[i], y[i], tt.elems().get(i), cost);
                    if (c != 0) yield c;
                }
                yield 0;
            }
            case Type.EnumT et -> {
                EnumV x = (EnumV) a;
                EnumV y = (EnumV) b;
                if (x.tag != y.tag) yield x.tag < y.tag ? -1 : 1;
                Variant v = et.decl().variants.get(x.tag);
                for (int i = 0; i < v.payload.size(); i++) {
                    cost.elements(1);
                    int c = compare(x.payload[i], y.payload[i], v.payload.get(i), cost);
                    if (c != 0) yield c;
                }
                yield 0;
            }
            default -> throw new IllegalStateException();
        };
    }

    private static int compareAll(Object[] x, Object[] y, int xn, int yn, Type elem, Meter cost) {
        for (int i = 0; i < Math.min(xn, yn); i++) {
            cost.elements(1);
            int c = compare(x[i], y[i], elem, cost);
            if (c != 0) return c;
        }
        return Integer.compare(xn, yn);
    }

    /**
     * Sorts a str's bytes, or a list's or an array's elements, in {@link #compare} order. Elements go through
     * {@link #mergeSort}, whose comparisons, and so its cost, are ALang's own on every JVM.
     */
    static void sort(Object xs, Type t, Meter cost) {
        switch (xs) {
            case StrV s -> {
                // Bytes sort as unsigned numbers.
                cost.bytes(4L * s.len);
                for (int i = 0; i < s.len; i++) s.b[i] ^= (byte) 0x80;
                Arrays.sort(s.b, 0, s.len);
                for (int i = 0; i < s.len; i++) s.b[i] ^= (byte) 0x80;
            }
            case ListV l -> mergeSort(l.items, l.len, ((Type.ListT) t).elem(), cost);
            default -> mergeSort(((ArrV) xs).items, ((ArrV) xs).items.length, ((Type.ArrayT) t).elem(), cost);
        }
    }

    /**
     * A stable merge sort of a[0..n): each comparison costs {@link Meter#COMPARISON} and what {@link #compare}
     * costs; a half already in order after the other is not merged, so sorted input takes n comparisons.
     */
    private static void mergeSort(Object[] a, int n, Type elem, Meter cost) {
        if (n < 2) return;
        cost.slots(n);
        sortRange(a, new Object[n], 0, n, elem, cost);
    }

    private static void sortRange(Object[] a, Object[] buf, int lo, int hi, Type elem, Meter cost) {
        if (hi - lo < 2) return;
        int mid = (lo + hi) >>> 1;
        sortRange(a, buf, lo, mid, elem, cost);
        sortRange(a, buf, mid, hi, elem, cost);
        cost.units(Meter.COMPARISON);
        if (compare(a[mid - 1], a[mid], elem, cost) <= 0) return;
        cost.slots(mid - lo);
        System.arraycopy(a, lo, buf, lo, mid - lo);
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
            cost.units(Meter.COMPARISON);
            // Equal elements keep their order: the left one first.
            a[k++] = compare(a[j], buf[i], elem, cost) < 0 ? a[j++] : buf[i++];
        }
        while (i < mid) a[k++] = buf[i++];
    }

    static void reverse(Object xs, Meter cost) {
        cost.units(switch (xs) {
            case StrV s -> s.len;
            case ListV l -> l.len;
            default -> ((ArrV) xs).items.length;
        });
        switch (xs) {
            case StrV s -> {
                for (int i = 0, j = s.len - 1; i < j; i++, j--) {
                    byte x = s.b[i];
                    s.b[i] = s.b[j];
                    s.b[j] = x;
                }
            }
            case ListV l -> reverse(l.items, l.len);
            default -> reverse(((ArrV) xs).items, ((ArrV) xs).items.length);
        }
    }

    private static void reverse(Object[] items, int n) {
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            Object x = items[i];
            items[i] = items[j];
            items[j] = x;
        }
    }

    /** Inserts v at position i: R0003 unless 0 <= i <= len, then R0004 if there is no room. */
    static void insert(Object xs, long i, PrimKind ik, Object v, Span at, Meter cost) {
        boolean str = xs instanceof StrV;
        int len = str ? ((StrV) xs).len : ((ListV) xs).len;
        int cap = str ? ((StrV) xs).b.length : ((ListV) xs).items.length;
        if (!within(i, len)) {
            throw new Trap(Code.R0003, at, "insert position " + IntOps.toString(ik, i) + " is out of range for length " + len);
        }
        if (len == cap) {
            throw new Trap(Code.R0004, at, "insert into a full " + (str ? "str" : "list") + " (capacity " + cap + ")");
        }
        int at0 = (int) i;
        cost.slots(len - at0);
        if (xs instanceof StrV s) {
            System.arraycopy(s.b, at0, s.b, at0 + 1, len - at0);
            s.b[at0] = (byte) (long) (Long) v;
            s.len++;
        } else {
            ListV l = (ListV) xs;
            System.arraycopy(l.items, at0, l.items, at0 + 1, len - at0);
            l.items[at0] = v;
            l.len++;
        }
    }

    /** Removes and returns the element at position i: R0003 unless 0 <= i < len. */
    static Object remove(Object xs, long i, PrimKind ik, Span at, Meter cost) {
        boolean str = xs instanceof StrV;
        int len = str ? ((StrV) xs).len : ((ListV) xs).len;
        if (!within(i, len) || i == len) {
            throw new Trap(Code.R0003, at, "index " + IntOps.toString(ik, i) + " is out of bounds for length " + len);
        }
        int at0 = (int) i;
        cost.slots(len - at0);
        if (xs instanceof StrV s) {
            long v = s.b[at0] & 0xFF;
            System.arraycopy(s.b, at0 + 1, s.b, at0, len - at0 - 1);
            s.len--;
            return v;
        }
        ListV l = (ListV) xs;
        Object v = l.items[at0];
        System.arraycopy(l.items, at0 + 1, l.items, at0, len - at0 - 1);
        l.items[--l.len] = null;
        return v;
    }

    // =================================================================== maps and sets

    /**
     * The position of k among keys[0..len), which are in order: its index, or -(where it would go) - 1. A binary
     * search: each key it compares with costs an element and what {@link #compare} costs.
     */
    static int find(Object[] keys, int len, Object k, Type kt, Meter cost) {
        int lo = 0;
        int hi = len - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            cost.elements(1);
            int c = compare(keys[mid], k, kt, cost);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -lo - 1;
    }

    private static byte[] keyText(Object k, Type kt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Format.format(ByteSink.of(out), k, kt, true);
        return out.toByteArray();
    }

    /** m[k]: R0003 if the map has no key k. */
    static Object mapGet(MapV m, Object k, Type kt, Span at, Meter cost) {
        int i = find(m.keys, m.len, k, kt, cost);
        if (i < 0) {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            msg.writeBytes("key ".getBytes(StandardCharsets.UTF_8));
            msg.writeBytes(keyText(k, kt));
            msg.writeBytes(" is not in the map".getBytes(StandardCharsets.UTF_8));
            throw new Trap(Code.R0003, at, msg.toByteArray());
        }
        return m.vals[i];
    }

    /** m[k] = v, or insert(mut m, k, v): replaces the value of k, or adds k in its place; R0004 if that does not fit. */
    static void mapPut(MapV m, Object k, Object v, Type kt, Span at, Meter cost) {
        int i = find(m.keys, m.len, k, kt, cost);
        if (i >= 0) {
            m.vals[i] = v;
            return;
        }
        if (m.len == m.keys.length) throw new Trap(Code.R0004, at, "insert into a full map (capacity " + m.keys.length + ")");
        int at0 = -i - 1;
        cost.slots(2L * (m.len - at0));
        System.arraycopy(m.keys, at0, m.keys, at0 + 1, m.len - at0);
        System.arraycopy(m.vals, at0, m.vals, at0 + 1, m.len - at0);
        m.keys[at0] = k;
        m.vals[at0] = v;
        m.len++;
    }

    /** remove(mut m, k): whether k was there. */
    static boolean mapRemove(MapV m, Object k, Type kt, Meter cost) {
        int i = find(m.keys, m.len, k, kt, cost);
        if (i < 0) return false;
        cost.slots(2L * (m.len - i));
        System.arraycopy(m.keys, i + 1, m.keys, i, m.len - i - 1);
        System.arraycopy(m.vals, i + 1, m.vals, i, m.len - i - 1);
        m.len--;
        m.keys[m.len] = null;
        m.vals[m.len] = null;
        return true;
    }

    /** insert(mut s, x): adds x in its place unless it is there; R0004 if that does not fit. */
    static void setInsert(ListV s, Object x, Type t, Span at, Meter cost) {
        int i = find(s.items, s.len, x, t, cost);
        if (i >= 0) return;
        if (s.len == s.items.length) throw new Trap(Code.R0004, at, "insert into a full set (capacity " + s.items.length + ")");
        int at0 = -i - 1;
        cost.slots(s.len - at0);
        System.arraycopy(s.items, at0, s.items, at0 + 1, s.len - at0);
        s.items[at0] = x;
        s.len++;
    }

    static boolean setRemove(ListV s, Object x, Type t, Meter cost) {
        int i = find(s.items, s.len, x, t, cost);
        if (i < 0) return false;
        cost.slots(s.len - i);
        System.arraycopy(s.items, i + 1, s.items, i, s.len - i - 1);
        s.items[--s.len] = null;
        return true;
    }

    // =================================================================== helpers

    /** The text of a primitive value, as print shows it. */
    private static String text(Object v, Type t) {
        PrimKind k = Type.kind(t);
        if (k.isFloat) return Format.formatFloat((Double) v);
        return IntOps.toString(k, (Long) v);
    }
}
