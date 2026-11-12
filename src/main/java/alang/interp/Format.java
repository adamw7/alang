package alang.interp;

import alang.ast.Ast.EnumDecl;
import alang.ast.Ast.Field;
import alang.ast.Ast.Variant;
import alang.interp.Values.ArrV;
import alang.interp.Values.EnumV;
import alang.interp.Values.ListV;
import alang.interp.Values.StrV;
import alang.interp.Values.StructV;
import alang.types.PrimKind;
import alang.types.Type;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;

/**
 * The text form of values, used by print() and append(). The C backend produces exactly
 * the same bytes.
 * <ul>
 *   <li>integers in decimal; bool as true/false</li>
 *   <li>floats rounded to 6 decimals with trailing zeros removed (1.5, 2.0, 0.333333), nan, inf, -inf</li>
 *   <li>top-level strings as their raw bytes; strings inside aggregates quoted and escaped</li>
 *   <li>arrays and lists as [a, b]; structs as Name(field: v, ...); enums as Enum.Variant(v, ...)</li>
 * </ul>
 */
public final class Format {
    private Format() {}

    private static final String HEX = "0123456789abcdef";

    /** The most decimals fixed() writes, and the widest pad_left and pad_right make. */
    public static final int MAX_DECIMALS = 20;
    public static final int MAX_PAD = 1 << 20;

    public static String formatFloat(double d) {
        String s = formatFixed(d, 6);
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (s.charAt(end - 1) == '0' && s.charAt(end - 2) != '.') end--;
        return s.substring(0, end);
    }

    /**
     * {@code d} with exactly {@code decimals} digits after the point (none and no point for 0), its exact binary
     * value rounded half to even, as the C backend's al_fmt_dec does: nan, inf and -inf as print writes them.
     */
    public static String formatFixed(double d, int decimals) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return d > 0 ? "inf" : "-inf";
        boolean negative = Math.copySign(1.0, d) < 0;
        return (negative ? "-" : "") + new BigDecimal(Math.abs(d)).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
    }

    public static void format(ByteSink out, Object v, Type t, boolean nested) {
        switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                String s;
                if (k == PrimKind.BOOL) s = ((Boolean) v) ? "true" : "false";
                else if (k.isFloat) s = formatFloat((Double) v);
                else s = IntOps.toString(k, (Long) v);
                out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
            }
            case Type.Str s -> {
                StrV sv = (StrV) v;
                if (!nested) {
                    out.write(sv.b, 0, sv.len);
                } else {
                    quote(out, sv.b, sv.len);
                }
            }
            case Type.ArrayT a -> {
                ArrV av = (ArrV) v;
                list(out, av.items, av.items.length, a.elem());
            }
            case Type.ListT l -> {
                ListV lv = (ListV) v;
                list(out, lv.items, lv.len, l.elem());
            }
            case Type.StructT st -> {
                StructV sv = (StructV) v;
                ascii(out, st.decl().name);
                out.write('(');
                int i = 0;
                for (Field f : st.decl().fields) {
                    if (i > 0) ascii(out, ", ");
                    ascii(out, f.name);
                    ascii(out, ": ");
                    format(out, sv.fields[i], f.type, true);
                    i++;
                }
                out.write(')');
            }
            case Type.TupleT tt -> {
                StructV sv = (StructV) v;
                out.write('(');
                for (int i = 0; i < tt.elems().size(); i++) {
                    if (i > 0) ascii(out, ", ");
                    format(out, sv.fields[i], tt.elems().get(i), true);
                }
                out.write(')');
            }
            case Type.EnumT et -> {
                EnumV ev = (EnumV) v;
                EnumDecl d = et.decl();
                Variant var = d.variants.get(ev.tag);
                ascii(out, d.name);
                out.write('.');
                ascii(out, var.name);
                if (!var.payload.isEmpty()) {
                    out.write('(');
                    for (int i = 0; i < var.payload.size(); i++) {
                        if (i > 0) ascii(out, ", ");
                        format(out, ev.payload[i], var.payload.get(i), true);
                    }
                    out.write(')');
                }
            }
            default -> ascii(out, "<?>");
        }
    }

    private static void list(ByteSink out, Object[] items, int n, Type elem) {
        out.write('[');
        for (int i = 0; i < n; i++) {
            if (i > 0) ascii(out, ", ");
            format(out, items[i], elem, true);
        }
        out.write(']');
    }

    private static void quote(ByteSink out, byte[] b, int len) {
        out.write('"');
        for (int i = 0; i < len; i++) {
            int c = b[i] & 0xFF;
            switch (c) {
                case '"' -> ascii(out, "\\\"");
                case '\\' -> ascii(out, "\\\\");
                case '\n' -> ascii(out, "\\n");
                case '\t' -> ascii(out, "\\t");
                case '\r' -> ascii(out, "\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        ascii(out, "\\x" + HEX.charAt(c >> 4) + HEX.charAt(c & 15));
                    } else {
                        out.write(c);
                    }
                }
            }
        }
        out.write('"');
    }

    private static void ascii(ByteSink out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String toText(Object v, Type t) {
        return new String(toBytes(v, t), StandardCharsets.UTF_8);
    }

    /** The text of a value as it appears inside a trap message, byte for byte. */
    public static byte[] toBytes(Object v, Type t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        format(ByteSink.of(out), v, t, true);
        return out.toByteArray();
    }
}
