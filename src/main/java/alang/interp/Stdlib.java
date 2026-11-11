package alang.interp;

import alang.ast.Ast.Variant;
import alang.diag.Code;
import alang.diag.Span;
import alang.interp.Values.ArrV;
import alang.interp.Values.EnumV;
import alang.interp.Values.ListV;
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

    static StrV mapCase(StrV s, boolean upper) {
        StrV r = new StrV(s.b.clone(), s.len);
        for (int i = 0; i < r.len; i++) {
            long c = r.b[i] & 0xFF;
            r.b[i] = (byte) (upper ? toUpper(c) : toLower(c));
        }
        return r;
    }

    // =================================================================== text

    /** What a text function looks for: the bytes of a str, or a single u8 byte. */
    static byte[] pattern(Object v) {
        return v instanceof StrV s ? Arrays.copyOf(s.b, s.len) : new byte[] {(byte) (long) (Long) v};
    }

    /** The first position at or after {@code from} where {@code n} occurs in the first {@code hn} bytes of h, or -1. */
    static int find(byte[] h, int hn, byte[] n, int from) {
        for (int i = from; i + n.length <= hn; i++) {
            if (Arrays.equals(h, i, i + n.length, n, 0, n.length)) return i;
        }
        return -1;
    }

    static boolean startsWith(StrV s, byte[] p) {
        return p.length <= s.len && Arrays.equals(s.b, 0, p.length, p, 0, p.length);
    }

    static boolean endsWith(StrV s, byte[] p) {
        return p.length <= s.len && Arrays.equals(s.b, s.len - p.length, s.len, p, 0, p.length);
    }

    /** The position of the first element of a list or array equal to x, or -1. */
    static long indexOf(Object xs, Object x, Type elem) {
        Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
        int n = xs instanceof ListV l ? l.len : items.length;
        for (int i = 0; i < n; i++) {
            if (Values.equal(items[i], x, elem)) return i;
        }
        return -1;
    }

    static StrV trim(StrV s) {
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
    static Object slice(Object xs, Type t, long from, PrimKind fk, long to, PrimKind tk, Span at) {
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
            byte[] b = new byte[s.b.length];
            System.arraycopy(s.b, f, b, 0, n);
            return new StrV(b, n);
        }
        Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
        Object[] out = new Object[((Type.ListT) t).cap()];
        for (int i = 0; i < n; i++) out[i] = Values.copy(items[f + i]);
        return new ListV(out, n);
    }

    /**
     * The pieces of s between occurrences of sep, or (sep null) between runs of whitespace, as a value of
     * type t. Each piece is checked in turn: first that the list has room for it, then that it fits.
     */
    static ListV split(String name, StrV s, byte[] sep, Type.ListT t, Span at) {
        int k = ((Type.Str) t.elem()).cap();
        ListV r = new ListV(new Object[t.cap()], 0);
        if (sep != null) {
            if (sep.length == 0) throw new Trap(Code.R0013, at, "split separator is empty");
            int pos = 0;
            while (true) {
                int hit = find(s.b, s.len, sep, pos);
                int end = hit < 0 ? s.len : hit;
                piece(name, r, s, pos, end, k, t, at);
                if (hit < 0) break;
                pos = end + sep.length;
            }
        } else {
            int i = 0;
            while (true) {
                while (i < s.len && isSpace(s.b[i] & 0xFF)) i++;
                if (i == s.len) break;
                int start = i;
                while (i < s.len && !isSpace(s.b[i] & 0xFF)) i++;
                piece(name, r, s, start, i, k, t, at);
            }
        }
        return r;
    }

    private static void piece(String name, ListV r, StrV s, int from, int to, int k, Type.ListT t, Span at) {
        if (r.len == r.items.length) {
            throw new Trap(Code.R0004, at, name + " makes more than " + r.items.length + " pieces, the capacity of " + t);
        }
        if (to - from > k) {
            throw new Trap(Code.R0004, at, name + " piece of " + (to - from) + " bytes exceeds str capacity " + k);
        }
        byte[] b = new byte[k];
        System.arraycopy(s.b, from, b, 0, to - from);
        r.items[r.len++] = new StrV(b, to - from);
    }

    static StrV join(Object parts, byte[] sep, Type.Str t, Span at) {
        Object[] items = parts instanceof ListV l ? l.items : ((ArrV) parts).items;
        int n = parts instanceof ListV l ? l.len : items.length;
        long need = 0;
        for (int i = 0; i < n; i++) need += ((StrV) items[i]).len + (i > 0 ? sep.length : 0);
        if (need > t.cap()) {
            throw new Trap(Code.R0004, at, "join needs " + need + " bytes, more than str capacity " + t.cap());
        }
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

    static StrV replace(StrV s, byte[] from, byte[] to, Type.Str t, Span at) {
        if (from.length == 0) throw new Trap(Code.R0013, at, "replace pattern is empty");
        long need = s.len;
        for (int hit = find(s.b, s.len, from, 0); hit >= 0; hit = find(s.b, s.len, from, hit + from.length)) {
            need += to.length - from.length;
        }
        if (need > t.cap()) {
            throw new Trap(Code.R0004, at, "replace needs " + need + " bytes, more than str capacity " + t.cap());
        }
        StrV r = new StrV(new byte[t.cap()], 0);
        int pos = 0;
        for (int hit = find(s.b, s.len, from, 0); hit >= 0; hit = find(s.b, s.len, from, pos)) {
            put(r, s.b, pos, hit - pos);
            put(r, to, 0, to.length);
            pos = hit + from.length;
        }
        put(r, s.b, pos, s.len - pos);
        return r;
    }

    /**
     * The value of decimal text: an optional + or -, then at least one digit, nothing else. Null if the
     * text has another form; a value of any size otherwise.
     */
    private static BigInteger decimal(StrV s) {
        int i = 0;
        if (s.len > 0 && (s.b[0] == '+' || s.b[0] == '-')) i = 1;
        if (i == s.len) return null;
        for (int j = i; j < s.len; j++) {
            if (!isDigit(s.b[j])) return null;
        }
        BigInteger v = new BigInteger(new String(s.b, i, s.len - i, StandardCharsets.US_ASCII));
        return s.b[0] == '-' ? v.negate() : v;
    }

    static boolean isInt(StrV s) {
        BigInteger v = decimal(s);
        return v != null && PrimKind.I64.fits(v);
    }

    /** The conversion of text to an integer type: R0009 unless it is decimal text that fits. */
    static long parseInt(StrV s, PrimKind to, Span at) {
        BigInteger v = decimal(s);
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

    // =================================================================== collections

    /**
     * The order sort uses, defined for every type: numbers by value (-0.0 before 0.0, every NaN after
     * everything else and equal to the others), false before true, strs, arrays and lists element by element
     * (a prefix first), structs field by field, enums by variant and then payload. Values it calls equal
     * cannot be told apart, so every correct sorting algorithm gives the same result.
     */
    static int compare(Object a, Object b, Type t) {
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
                yield Integer.signum(Arrays.compareUnsigned(x.b, 0, x.len, y.b, 0, y.len));
            }
            case Type.ArrayT at -> compareAll(((ArrV) a).items, ((ArrV) b).items, at.len(), at.len(), at.elem());
            case Type.ListT l -> compareAll(((ListV) a).items, ((ListV) b).items, ((ListV) a).len, ((ListV) b).len, l.elem());
            case Type.StructT st -> {
                Object[] x = ((StructV) a).fields;
                Object[] y = ((StructV) b).fields;
                for (int i = 0; i < x.length; i++) {
                    int c = compare(x[i], y[i], st.decl().fields.get(i).type);
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
                    int c = compare(x.payload[i], y.payload[i], v.payload.get(i));
                    if (c != 0) yield c;
                }
                yield 0;
            }
            default -> throw new IllegalStateException();
        };
    }

    private static int compareAll(Object[] x, Object[] y, int xn, int yn, Type elem) {
        for (int i = 0; i < Math.min(xn, yn); i++) {
            int c = compare(x[i], y[i], elem);
            if (c != 0) return c;
        }
        return Integer.compare(xn, yn);
    }

    static void sort(Object xs, Type t) {
        switch (xs) {
            case StrV s -> {
                // Bytes sort as unsigned numbers.
                for (int i = 0; i < s.len; i++) s.b[i] ^= (byte) 0x80;
                Arrays.sort(s.b, 0, s.len);
                for (int i = 0; i < s.len; i++) s.b[i] ^= (byte) 0x80;
            }
            case ListV l -> Arrays.sort(l.items, 0, l.len, (a, b) -> compare(a, b, ((Type.ListT) t).elem()));
            default -> Arrays.sort(((ArrV) xs).items, (a, b) -> compare(a, b, ((Type.ArrayT) t).elem()));
        }
    }

    static void reverse(Object xs) {
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
    static void insert(Object xs, long i, PrimKind ik, Object v, Span at) {
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
    static Object remove(Object xs, long i, PrimKind ik, Span at) {
        boolean str = xs instanceof StrV;
        int len = str ? ((StrV) xs).len : ((ListV) xs).len;
        if (!within(i, len) || i == len) {
            throw new Trap(Code.R0003, at, "index " + IntOps.toString(ik, i) + " is out of bounds for length " + len);
        }
        int at0 = (int) i;
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

    // =================================================================== helpers

    /** The text of a primitive value, as print shows it. */
    private static String text(Object v, Type t) {
        PrimKind k = Type.kind(t);
        if (k.isFloat) return Format.formatFloat((Double) v);
        return IntOps.toString(k, (Long) v);
    }
}
