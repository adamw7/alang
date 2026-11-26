package alang.interp;

import alang.ast.Ast.*;
import alang.diag.*;
import alang.interp.Values.*;
import alang.types.*;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.nio.charset.StandardCharsets;

/**
 * to_json: the JSON text of a value, byte for byte as the C prelude's al_je_ functions and al_json_float write it. The
 * forms are those of tool results and from_json: structs as objects, enum variants as "Name" or {"Name": [values]},
 * maps with str keys as objects and other maps as [[key, value], ...], arrays, lists, sets and tuples as arrays.
 * One line, with a space after each comma and colon, as every JSON the toolchain prints.
 */
public final class JsonText {
    private JsonText() {}

    /** Appends the JSON of v, a value of type t; traps (R0013) on NaN, infinity and str values that are not UTF-8. */
    static void write(ByteArrayOutputStream out, Object v, Type t, Span at, Meter meter) {
        meter.elements(1);
        switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                if (k == PrimKind.BOOL) {
                    ascii(out, (Boolean) v ? "true" : "false");
                } else if (k.isFloat) {
                    double d = (Double) v;
                    if (!Double.isFinite(d)) {
                        throw new Trap(Code.R0013, at, "to_json() cannot write " + (Double.isNaN(d) ? "nan" : d > 0 ? "inf" : "-inf")
                            + ": JSON has no NaN or infinity");
                    }
                    ascii(out, floatText(d, k == PrimKind.F32));
                } else {
                    ascii(out, IntOps.toString(k, (Long) v));
                }
            }
            case Type.Str s -> quote(out, (StrV) v, at, meter);
            case Type.ArrayT a -> {
                Object[] items = ((ArrV) v).items;
                elements(out, items, items.length, a.elem(), at, meter);
            }
            case Type.ListT l -> elements(out, ((ListV) v).items, ((ListV) v).len, l.elem(), at, meter);
            case Type.SetT st -> elements(out, ((ListV) v).items, ((ListV) v).len, st.elem(), at, meter);
            case Type.TupleT tt -> {
                out.write('[');
                for (int i = 0; i < tt.elems().size(); i++) {
                    if (i > 0) ascii(out, ", ");
                    write(out, ((StructV) v).fields[i], tt.elems().get(i), at, meter);
                }
                out.write(']');
            }
            case Type.StructT st -> {
                out.write('{');
                int i = 0;
                for (Field f : st.decl().fields) {
                    if (i > 0) ascii(out, ", ");
                    name(out, f.name);
                    ascii(out, ": ");
                    write(out, ((StructV) v).fields[i++], f.type, at, meter);
                }
                out.write('}');
            }
            case Type.MapT mt -> {
                MapV m = (MapV) v;
                boolean strKeys = mt.key() instanceof Type.Str;
                out.write(strKeys ? '{' : '[');
                for (int i = 0; i < m.len; i++) {
                    if (i > 0) ascii(out, ", ");
                    if (strKeys) {
                        quote(out, (StrV) m.keys[i], at, meter);
                        ascii(out, ": ");
                        write(out, m.vals[i], mt.value(), at, meter);
                    } else {
                        out.write('[');
                        write(out, m.keys[i], mt.key(), at, meter);
                        ascii(out, ", ");
                        write(out, m.vals[i], mt.value(), at, meter);
                        out.write(']');
                    }
                }
                out.write(strKeys ? '}' : ']');
            }
            case Type.EnumT et -> {
                EnumV ev = (EnumV) v;
                Variant var = et.decl().variants.get(ev.tag);
                if (var.payload.isEmpty()) {
                    name(out, var.name);
                } else {
                    out.write('{');
                    name(out, var.name);
                    ascii(out, ": [");
                    for (int i = 0; i < var.payload.size(); i++) {
                        if (i > 0) ascii(out, ", ");
                        write(out, ev.payload[i], var.payload.get(i), at, meter);
                    }
                    ascii(out, "]}");
                }
            }
            default -> throw new IllegalStateException("no JSON for " + t);
        }
    }

    private static void elements(ByteArrayOutputStream out, Object[] items, int n, Type elem, Span at, Meter meter) {
        out.write('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) ascii(out, ", ");
            write(out, items[i], elem, at, meter);
        }
        out.write(']');
    }

    private static void ascii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    /** The name of a field or variant as a JSON string: an identifier, whose bytes need no escapes. */
    private static void name(ByteArrayOutputStream out, String name) {
        out.write('"');
        out.writeBytes(name.getBytes(StandardCharsets.UTF_8));
        out.write('"');
    }

    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** A str as a JSON string: " \ and control bytes escaped, every other byte as it is. */
    private static void quote(ByteArrayOutputStream out, StrV s, Span at, Meter meter) {
        if (!Stdlib.isUtf8(s, meter)) {
            throw new Trap(Code.R0013, at, "to_json() cannot write a str that is not UTF-8 text; to write its bytes, make it a "
                + "list[u8; N]");
        }
        out.write('"');
        for (int i = 0; i < s.len; i++) {
            int c = s.b[i] & 0xFF;
            switch (c) {
                case '"' -> ascii(out, "\\\"");
                case '\\' -> ascii(out, "\\\\");
                case '\n' -> ascii(out, "\\n");
                case '\r' -> ascii(out, "\\r");
                case '\t' -> ascii(out, "\\t");
                default -> {
                    if (c < 0x20) {
                        ascii(out, "\\u00");
                        out.write(HEX[c >> 4]);
                        out.write(HEX[c & 15]);
                    } else {
                        out.write(c);
                    }
                }
            }
        }
        out.write('"');
    }

    /**
     * The JSON text of a finite float: the decimal with the fewest significant digits that reads back as the same f64
     * (or f32), the one closest to it when several are that short, the even one of two as close. Written plainly
     * when it is at least 1e-7 and below 1e21, with a ".0" when it is whole (0.1, 100.0, -0.0), otherwise with an
     * exponent (1e+21, 1.5e-8), as JavaScript writes numbers. The C prelude's al_json_float finds the same digits.
     */
    public static String floatText(double d, boolean f32) {
        if (d == 0) return Math.copySign(1.0, d) < 0 ? "-0.0" : "0.0";
        StringBuilder sb = new StringBuilder();
        if (d < 0) {
            sb.append('-');
            d = -d;
        }
        long m;
        long frac;
        int e;
        int expf;
        if (f32) {
            int u = Float.floatToRawIntBits((float) d);
            expf = (u >>> 23) & 0xFF;
            frac = u & 0x7FFFFF;
            m = expf != 0 ? frac | 0x800000 : frac;
            e = (expf != 0 ? expf : 1) - 150;
        } else {
            long u = Double.doubleToRawLongBits(d);
            expf = (int) ((u >>> 52) & 0x7FF);
            frac = u & 0xFFFFFFFFFFFFFL;
            m = expf != 0 ? frac | (1L << 52) : frac;
            e = (expf != 0 ? expf : 1) - 1075;
        }
        // The values that read back as this one lie between the midpoints to its neighbours, which are both included
        // when its mantissa is even (a read rounds a tie to even). Just above a power of two the lower neighbour is
        // half as far.
        boolean lowerCloser = frac == 0 && expf > 1;
        boolean inclusive = (m & 1) == 0;
        BigDecimal lo = lowerCloser ? exact(4 * m - 1, e - 2) : exact(2 * m - 1, e - 1);
        BigDecimal hi = lowerCloser ? exact(4 * m + 2, e - 2) : exact(2 * m + 1, e - 1);
        BigDecimal v = exact(m, e);
        BigDecimal r;
        for (int p = 1; ; p++) {
            BigDecimal down = v.round(new MathContext(p, RoundingMode.FLOOR));
            BigDecimal up = v.round(new MathContext(p, RoundingMode.CEILING));
            boolean inDown = inside(down, lo, hi, inclusive);
            boolean inUp = inside(up, lo, hi, inclusive);
            if (!inDown && !inUp) continue;
            r = inDown && inUp ? v.round(new MathContext(p, RoundingMode.HALF_EVEN)) : inDown ? down : up;
            break;
        }
        r = r.stripTrailingZeros();
        sb.append(decimalText(r.unscaledValue().toString(), -r.scale()));
        return sb.toString();
    }

    private static BigDecimal exact(long n, int e) {
        BigInteger b = BigInteger.valueOf(n);
        return e >= 0 ? new BigDecimal(b.shiftLeft(e)) : new BigDecimal(b.multiply(BigInteger.valueOf(5).pow(-e)), -e);
    }

    private static boolean inside(BigDecimal x, BigDecimal lo, BigDecimal hi, boolean inclusive) {
        int a = x.compareTo(lo);
        int b = x.compareTo(hi);
        return inclusive ? a >= 0 && b <= 0 : a > 0 && b < 0;
    }

    /** The text of digits * 10^q, digits without leading or trailing zeros, as floatText describes. */
    static String decimalText(String digits, int q) {
        int k = digits.length();
        int x = q + k - 1;
        StringBuilder sb = new StringBuilder();
        if (x >= -7 && x < 21) {
            if (q >= 0) {
                sb.append(digits).append("0".repeat(q)).append(".0");
            } else if (x >= 0) {
                sb.append(digits, 0, k + q).append('.').append(digits, k + q, k);
            } else {
                sb.append("0.").append("0".repeat(-x - 1)).append(digits);
            }
        } else {
            sb.append(digits.charAt(0));
            if (k > 1) sb.append('.').append(digits, 1, k);
            sb.append('e').append(x < 0 ? '-' : '+').append(Math.abs(x));
        }
        return sb.toString();
    }
}
