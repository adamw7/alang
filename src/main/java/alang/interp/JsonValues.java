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

    public static Object toJson(Object v, Type t) {
        return switch (t) {
            case Type.Prim p -> {
                PrimKind k = p.kind();
                if (k == PrimKind.BOOL) yield v;
                if (k.isFloat) {
                    double d = (Double) v;
                    yield Double.isFinite(d) ? new BigDecimal(Double.toString(d)) : null;
                }
                yield IntOps.toBig(k, (Long) v);
            }
            case Type.Str s -> {
                StrV sv = (StrV) v;
                yield new String(sv.b, 0, sv.len, StandardCharsets.UTF_8);
            }
            case Type.ArrayT a -> {
                List<Object> out = new ArrayList<>();
                for (Object o : ((ArrV) v).items) out.add(toJson(o, a.elem()));
                yield out;
            }
            case Type.ListT l -> {
                ListV lv = (ListV) v;
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < lv.len; i++) out.add(toJson(lv.items[i], l.elem()));
                yield out;
            }
            case Type.StructT st -> {
                Map<String, Object> out = new LinkedHashMap<>();
                int i = 0;
                for (Field f : st.decl().fields) out.put(f.name, toJson(((StructV) v).fields[i++], f.type));
                yield out;
            }
            case Type.EnumT et -> {
                EnumV ev = (EnumV) v;
                Variant var = et.decl().variants.get(ev.tag);
                if (var.payload.isEmpty()) yield var.name;
                List<Object> vals = new ArrayList<>();
                for (int i = 0; i < var.payload.size(); i++) vals.add(toJson(ev.payload[i], var.payload.get(i)));
                yield Json.obj(var.name, vals);
            }
            default -> null;
        };
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
                    double d;
                    if (j instanceof BigInteger bi) d = bi.doubleValue();
                    else if (j instanceof BigDecimal bd) d = bd.doubleValue();
                    else throw fail(path, "expected a number");
                    return k == PrimKind.F32 ? (double) (float) d : d;
                }
                BigInteger v;
                if (j instanceof BigInteger bi) {
                    v = bi;
                } else if (j instanceof BigDecimal bd) {
                    try {
                        v = bd.toBigIntegerExact();
                    } catch (ArithmeticException e) {
                        throw fail(path, "expected an integer, found " + bd);
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
