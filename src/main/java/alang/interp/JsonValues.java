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
import alang.util.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping between ALang values and JSON, used by tool calls.
 * <ul>
 *   <li>bool, integers, floats map to JSON booleans and numbers</li>
 *   <li>str[N] maps to a string of at most N UTF-8 bytes</li>
 *   <li>[T; N] and list[T; N] map to arrays (exactly N / at most N items)</li>
 *   <li>structs map to objects with every field present</li>
 *   <li>enum variants without payload map to "Variant"; with payload to {"Variant": [values...]}</li>
 * </ul>
 */
public final class JsonValues {
    private JsonValues() {}

    public static final class DecodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DecodeException(String message) {
            super(message);
        }
    }

    /** A result that JSON cannot represent: NaN or an infinity. */
    public static final class EncodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Where the value is, relative to the one being encoded: "", ".items[2]". */
        private final String path;
        private final String problem;

        EncodeException(String path, String problem) {
            super(path + ": " + problem);
            this.path = path;
            this.problem = problem;
        }

        /** The same problem, one level further out: inside {@code segment}. */
        EncodeException within(String segment) {
            return new EncodeException(segment + path, problem);
        }

        /** The message, with the path starting at {@code root}: "result.items[2]: ...". */
        public String messageAt(String root) {
            return root + path + ": " + problem;
        }
    }

    // ------------------------------------------------------------------ schema

    public static Map<String, Object> schema(Type t) {
        return switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                if (k == PrimKind.BOOL) yield Json.obj("type", "boolean");
                if (k.isFloat) yield Json.obj("type", "number");
                yield Json.obj("type", "integer", "minimum", k.min(), "maximum", k.max());
            }
            case Type.Str s -> Json.obj("type", "string", "maxLength", s.cap(),
                "description", "UTF-8 text of at most " + s.cap() + " bytes");
            case Type.ArrayT a -> Json.obj("type", "array", "items", schema(a.elem()), "minItems", a.len(), "maxItems", a.len());
            case Type.ListT l -> Json.obj("type", "array", "items", schema(l.elem()), "maxItems", l.cap());
            case Type.StructT st -> {
                Map<String, Object> props = new LinkedHashMap<>();
                List<Object> required = new ArrayList<>();
                for (Field f : st.decl().fields) {
                    props.put(f.name, schema(f.type));
                    required.add(f.name);
                }
                yield Json.obj("type", "object", "properties", props, "required", required, "additionalProperties", false);
            }
            case Type.EnumT et -> enumSchema(et.decl());
            default -> Json.obj();
        };
    }

    private static Map<String, Object> enumSchema(EnumDecl d) {
        List<Object> units = new ArrayList<>();
        List<Object> alternatives = new ArrayList<>();
        for (Variant v : d.variants) {
            if (v.payload.isEmpty()) {
                units.add(v.name);
            } else {
                List<Object> items = new ArrayList<>();
                for (Type t : v.payload) items.add(schema(t));
                Map<String, Object> arr = Json.obj("type", "array", "prefixItems", items,
                    "minItems", items.size(), "maxItems", items.size());
                alternatives.add(Json.obj("type", "object", "properties", Json.obj(v.name, arr),
                    "required", List.of(v.name), "additionalProperties", false));
            }
        }
        if (alternatives.isEmpty()) return Json.obj("type", "string", "enum", units);
        if (!units.isEmpty()) alternatives.add(0, Json.obj("type", "string", "enum", units));
        return Json.obj("oneOf", alternatives);
    }

    // ------------------------------------------------------------------ encode

    /**
     * The JSON form of a value.
     *
     * @throws EncodeException for NaN and infinities, which JSON cannot represent: writing them as
     *     null, as before, made a broken result look like a successful one
     */
    public static Object toJson(Object v, Type t) {
        return switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                if (k == PrimKind.BOOL) yield v;
                if (k.isFloat) {
                    double d = (Double) v;
                    if (!Double.isFinite(d)) {
                        throw new EncodeException("", "the " + k.name + " value " + Format.toText(v, t)
                            + " has no JSON representation");
                    }
                    // The shortest decimal that reads back as the same f32, as print shows it: 0.1, not
                    // the 0.10000000149011612 that the widened double would print.
                    yield new BigDecimal(k == PrimKind.F32 ? Float.toString((float) d) : Double.toString(d));
                }
                yield IntOps.toBig(k, (Long) v);
            }
            case Type.Str s -> {
                StrV sv = (StrV) v;
                yield new String(sv.b, 0, sv.len, StandardCharsets.UTF_8);
            }
            case Type.ArrayT a -> {
                List<Object> out = new ArrayList<>();
                Object[] items = ((ArrV) v).items;
                for (int i = 0; i < items.length; i++) out.add(element(items[i], a.elem(), i));
                yield out;
            }
            case Type.ListT l -> {
                ListV lv = (ListV) v;
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < lv.len; i++) out.add(element(lv.items[i], l.elem(), i));
                yield out;
            }
            case Type.StructT st -> {
                Map<String, Object> out = new LinkedHashMap<>();
                int i = 0;
                for (Field f : st.decl().fields) {
                    try {
                        out.put(f.name, toJson(((StructV) v).fields[i++], f.type));
                    } catch (EncodeException e) {
                        throw e.within("." + f.name);
                    }
                }
                yield out;
            }
            case Type.EnumT et -> {
                EnumV ev = (EnumV) v;
                Variant var = et.decl().variants.get(ev.tag);
                if (var.payload.isEmpty()) yield var.name;
                List<Object> vals = new ArrayList<>();
                for (int i = 0; i < var.payload.size(); i++) {
                    try {
                        vals.add(toJson(ev.payload[i], var.payload.get(i)));
                    } catch (EncodeException e) {
                        throw e.within("." + var.name + "[" + i + "]");
                    }
                }
                yield Json.obj(var.name, vals);
            }
            default -> null;
        };
    }

    /** An element of an array or list; its path is built only for an error. */
    private static Object element(Object v, Type t, int i) {
        try {
            return toJson(v, t);
        } catch (EncodeException e) {
            throw e.within("[" + i + "]");
        }
    }

    // ------------------------------------------------------------------ decode

    public static Object fromJson(Object j, Type t, String path) {
        switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                if (k == PrimKind.BOOL) {
                    if (j instanceof Boolean b) return b;
                    throw fail(path, "expected a boolean");
                }
                if (k.isFloat) {
                    // Round once, straight to the target type: f32 via f64 would round twice.
                    boolean f32 = k == PrimKind.F32;
                    double d;
                    if (j instanceof BigInteger bi) d = f32 ? bi.floatValue() : bi.doubleValue();
                    else if (j instanceof BigDecimal bd) d = f32 ? bd.floatValue() : bd.doubleValue();
                    else throw fail(path, "expected a number");
                    // Too large a number rounds to an infinity, which is not the number that was sent.
                    if (Double.isInfinite(d)) throw fail(path, j + " is out of range for " + k.name);
                    return d;
                }
                BigInteger v;
                if (j instanceof BigInteger bi) {
                    v = bi;
                } else if (j instanceof BigDecimal bd) {
                    // Decide from the digit count first: converting a number such as 1e100000000 or
                    // 1e-100000000 exactly would take minutes for a value no integer type can hold.
                    long intDigits = (long) bd.precision() - bd.scale();
                    if (bd.signum() == 0) {
                        v = BigInteger.ZERO;
                    } else if (intDigits > 20) {
                        throw fail(path, bd + " is out of range for " + k.name + " (" + k.min() + ".." + k.max() + ")");
                    } else if (intDigits <= 0) {
                        throw fail(path, "expected an integer, found " + bd);
                    } else {
                        try {
                            v = bd.toBigIntegerExact();
                        } catch (ArithmeticException e) {
                            throw fail(path, "expected an integer, found " + bd);
                        }
                    }
                } else {
                    throw fail(path, "expected an integer");
                }
                if (!k.fits(v)) throw fail(path, v + " is out of range for " + k.name + " (" + k.min() + ".." + k.max() + ")");
                return v.longValue();
            }
            case Type.Str s -> {
                if (!(j instanceof String str)) throw fail(path, "expected a string");
                byte[] b = str.getBytes(StandardCharsets.UTF_8);
                if (b.length > s.cap()) throw fail(path, "string is " + b.length + " bytes, capacity is " + s.cap());
                byte[] buf = new byte[s.cap()];
                System.arraycopy(b, 0, buf, 0, b.length);
                return new StrV(buf, b.length);
            }
            case Type.ArrayT a -> {
                if (!(j instanceof List<?> l)) throw fail(path, "expected an array");
                if (l.size() != a.len()) throw fail(path, "expected exactly " + a.len() + " items, found " + l.size());
                Object[] items = new Object[a.len()];
                for (int i = 0; i < items.length; i++) items[i] = fromJson(l.get(i), a.elem(), path + "[" + i + "]");
                return new ArrV(items);
            }
            case Type.ListT lt -> {
                if (!(j instanceof List<?> l)) throw fail(path, "expected an array");
                if (l.size() > lt.cap()) throw fail(path, "at most " + lt.cap() + " items allowed, found " + l.size());
                Object[] items = new Object[lt.cap()];
                for (int i = 0; i < l.size(); i++) items[i] = fromJson(l.get(i), lt.elem(), path + "[" + i + "]");
                return new ListV(items, l.size());
            }
            case Type.StructT st -> {
                if (!(j instanceof Map<?, ?> m)) throw fail(path, "expected an object");
                for (Object key : m.keySet()) {
                    if (st.decl().fieldIndex((String) key) < 0) throw fail(path, "unknown field \"" + key + "\"");
                }
                Object[] fields = new Object[st.decl().fields.size()];
                int i = 0;
                for (Field f : st.decl().fields) {
                    if (!m.containsKey(f.name)) throw fail(path, "missing field \"" + f.name + "\"");
                    fields[i++] = fromJson(m.get(f.name), f.type, path + "." + f.name);
                }
                return new StructV(fields);
            }
            case Type.EnumT et -> {
                EnumDecl d = et.decl();
                if (j instanceof String name) {
                    Variant v = d.variant(name);
                    if (v == null) throw fail(path, "unknown variant \"" + name + "\" of " + d.name);
                    if (!v.payload.isEmpty()) throw fail(path, "variant " + name + " needs values: {\"" + name + "\": [...]}");
                    return new EnumV(v.index, new Object[0]);
                }
                if (j instanceof Map<?, ?> m && m.size() == 1) {
                    String name = (String) m.keySet().iterator().next();
                    Variant v = d.variant(name);
                    if (v == null) throw fail(path, "unknown variant \"" + name + "\" of " + d.name);
                    if (!(m.get(name) instanceof List<?> vals) || vals.size() != v.payload.size()) {
                        throw fail(path, "variant " + name + " needs an array of " + v.payload.size() + " value(s)");
                    }
                    Object[] p = new Object[vals.size()];
                    for (int i = 0; i < p.length; i++) p[i] = fromJson(vals.get(i), v.payload.get(i), path + "." + name + "[" + i + "]");
                    return new EnumV(v.index, p);
                }
                throw fail(path, "expected a variant name or {\"Variant\": [values]}");
            }
            default -> throw fail(path, "unsupported type");
        }
    }

    private static DecodeException fail(String path, String msg) {
        return new DecodeException(path + ": " + msg);
    }
}
