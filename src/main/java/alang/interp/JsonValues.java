package alang.interp;

import alang.ast.Ast.EnumDecl;
import alang.ast.Ast.Field;
import alang.ast.Ast.Variant;
import alang.interp.Values.ArrV;
import alang.interp.Values.EnumV;
import alang.interp.Values.ListV;
import alang.interp.Values.MapV;
import alang.interp.Values.StrV;
import alang.interp.Values.StructV;
import alang.types.PrimKind;
import alang.types.Type;
import alang.util.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
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

    /** A result that JSON cannot represent: NaN, an infinity, or a str that is not UTF-8 text. */
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

    /**
     * The JSON Schema of a type's JSON form. The `///` doc comments of structs, enums, fields and variants
     * become descriptions; every call returns new maps, which the caller may change.
     */
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
                    props.put(f.name, describe(schema(f.type), f.doc));
                    required.add(f.name);
                }
                yield describe(Json.obj("type", "object", "properties", props, "required", required,
                    "additionalProperties", false), st.decl().doc);
            }
            case Type.SetT st -> Json.obj("type", "array", "items", schema(st.elem()), "maxItems", st.cap(), "uniqueItems", true);
            case Type.MapT mt when mt.key() instanceof Type.Str ks -> Json.obj("type", "object", "additionalProperties",
                schema(mt.value()), "propertyNames", Json.obj("maxLength", ks.cap()), "maxProperties", mt.cap());
            case Type.MapT mt -> Json.obj("type", "array", "items", schema(new Type.TupleT(List.of(mt.key(), mt.value()))),
                "maxItems", mt.cap(), "description", "[key, value] pairs, each key once");
            case Type.TupleT tt -> {
                List<Object> items = new ArrayList<>();
                for (Type e : tt.elems()) items.add(schema(e));
                yield Json.obj("type", "array", "prefixItems", items, "items", false, "minItems", items.size(),
                    "maxItems", items.size());
            }
            case Type.EnumT et -> describe(enumSchema(et.decl()), et.decl().doc);
            default -> Json.obj();
        };
    }

    /**
     * {@code schema} with {@code doc} in front of its description: a field's or parameter's own doc comes
     * first, then what its type says, such as the doc of a struct or the byte limit of a str.
     */
    public static Map<String, Object> describe(Map<String, Object> schema, String doc) {
        if (doc.isEmpty()) return schema;
        Object own = schema.get("description");
        schema.put("description", own == null ? doc : doc + "\n" + own);
        return schema;
    }

    /**
     * Variants without a payload are one string enum, unless one of them is documented: an enum cannot
     * describe its values one by one, so then each becomes a {"const": name} alternative of its own.
     */
    private static Map<String, Object> enumSchema(EnumDecl d) {
        boolean documentedUnit = d.variants.stream().anyMatch(v -> v.payload.isEmpty() && !v.doc.isEmpty());
        List<Object> units = new ArrayList<>();
        List<Object> alternatives = new ArrayList<>();
        for (Variant v : d.variants) {
            if (v.payload.isEmpty()) {
                if (documentedUnit) {
                    alternatives.add(describe(Json.obj("type", "string", "const", v.name), v.doc));
                } else {
                    units.add(v.name);
                }
            } else {
                List<Object> items = new ArrayList<>();
                for (Type t : v.payload) items.add(schema(t));
                Map<String, Object> arr = Json.obj("type", "array", "prefixItems", items,
                    "minItems", items.size(), "maxItems", items.size());
                alternatives.add(describe(Json.obj("type", "object", "properties", Json.obj(v.name, arr),
                    "required", List.of(v.name), "additionalProperties", false), v.doc));
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
     * @throws EncodeException for NaN, infinities and str values that are not UTF-8, which JSON cannot
     *     represent: writing null or U+FFFD instead, as before, made a broken result look like a successful
     *     one
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
                            + " has no JSON representation; JSON has no NaN or infinity, so check for them in the tool "
                            + "and return an enum instead");
                    }
                    // BigDecimal has no negative zero, so a zero stays a double, which Json writes as -0.0 or 0.0.
                    if (d == 0) yield d;
                    // The shortest decimal that reads back as the same f32, as print shows it: 0.1, not
                    // the 0.10000000149011612 that the widened double would print.
                    BigDecimal shortest = new BigDecimal(k == PrimKind.F32 ? Float.toString((float) d) : Double.toString(d));
                    // A scale of 0 prints as an integer: 123456789.0 ("1.23456789E8") came back as 123456789,
                    // which JSON readers such as Python's turn into an int, while 1234567.0 stayed a float.
                    yield shortest.scale() == 0 ? shortest.setScale(1) : shortest;
                }
                yield IntOps.toBig(k, (Long) v);
            }
            case Type.Str s -> {
                StrV sv = (StrV) v;
                yield utf8Text(sv);
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
            case Type.TupleT tt -> {
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < tt.elems().size(); i++) out.add(element(((StructV) v).fields[i], tt.elems().get(i), i));
                yield out;
            }
            case Type.SetT st -> {
                ListV lv = (ListV) v;
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < lv.len; i++) out.add(element(lv.items[i], st.elem(), i));
                yield out;
            }
            case Type.MapT mt -> {
                MapV mv = (MapV) v;
                if (mt.key() instanceof Type.Str) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    for (int i = 0; i < mv.len; i++) {
                        String key = (String) toJson(mv.keys[i], mt.key());
                        try {
                            out.put(key, toJson(mv.vals[i], mt.value()));
                        } catch (EncodeException e) {
                            throw e.within("." + key);
                        }
                    }
                    yield out;
                }
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < mv.len; i++) {
                    out.add(List.of(element(mv.keys[i], mt.key(), i), element(mv.vals[i], mt.value(), i)));
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

    /**
     * The text of a str for a JSON string, which can only hold Unicode text. Decoding leniently, as before,
     * turned each byte that is not UTF-8 into U+FFFD, so different results came back as the same string.
     */
    private static String utf8Text(StrV sv) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(sv.b, 0, sv.len);
        CharBuffer out = CharBuffer.allocate(sv.len);
        CoderResult r = dec.decode(in, out, true);
        if (!r.isError()) r = dec.flush(out);
        if (r.isError()) {
            int at = in.position();
            throw new EncodeException("", "the str value is not UTF-8 text (byte 0x"
                + String.format(java.util.Locale.ROOT, "%02x", sv.b[at] & 0xFF) + " at offset " + at
                + "), so it has no JSON string; return its bytes as a list[u8; N] instead");
        }
        return out.flip().toString();
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
                    // Json.parse returns a Double only for a negative zero such as -0.0.
                    if (j instanceof Double negativeZero) d = negativeZero;
                    else if (j instanceof BigInteger bi) d = f32 ? bi.floatValue() : bi.doubleValue();
                    else if (j instanceof BigDecimal bd) d = f32 ? bd.floatValue() : bd.doubleValue();
                    else throw fail(path, "expected a number");
                    // Too large a number rounds to an infinity, which is not the number that was sent.
                    if (Double.isInfinite(d)) throw fail(path, j + " is out of range for " + k.name);
                    return d;
                }
                BigInteger v;
                if (j instanceof Double) {
                    // A negative zero such as -0.0: as an integer, simply 0.
                    v = BigInteger.ZERO;
                } else if (j instanceof BigInteger bi) {
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
            case Type.SetT st -> {
                if (!(j instanceof List<?> l)) throw fail(path, "expected an array");
                if (l.size() > st.cap()) throw fail(path, "at most " + st.cap() + " items allowed, found " + l.size());
                ListV s = new ListV(new Object[st.cap()], 0);
                // Arguments are read before the call, and cost it nothing.
                Meter free = new Meter(0);
                for (int i = 0; i < l.size(); i++) {
                    Object x = fromJson(l.get(i), st.elem(), path + "[" + i + "]");
                    if (Stdlib.find(s.items, s.len, x, st.elem(), free) >= 0) throw fail(path + "[" + i + "]", "duplicate item");
                    Stdlib.setInsert(s, x, st.elem(), null, free);
                }
                return s;
            }
            case Type.MapT mt -> {
                MapV m = new MapV(new Object[mt.cap()], new Object[mt.cap()], 0);
                List<Object[]> entries = new ArrayList<>();
                if (mt.key() instanceof Type.Str) {
                    if (!(j instanceof Map<?, ?> o)) throw fail(path, "expected an object");
                    for (Map.Entry<?, ?> e : o.entrySet()) entries.add(new Object[] {e.getKey(), e.getValue(), "." + e.getKey()});
                } else {
                    if (!(j instanceof List<?> l)) throw fail(path, "expected an array of [key, value] pairs");
                    for (int i = 0; i < l.size(); i++) {
                        if (!(l.get(i) instanceof List<?> pair) || pair.size() != 2) throw fail(path + "[" + i + "]", "expected a [key, value] pair");
                        entries.add(new Object[] {pair.get(0), pair.get(1), "[" + i + "]"});
                    }
                }
                if (entries.size() > mt.cap()) throw fail(path, "at most " + mt.cap() + " entries allowed, found " + entries.size());
                Meter free = new Meter(0);
                for (Object[] e : entries) {
                    Object k = fromJson(e[0], mt.key(), path + e[2] + "(key)");
                    if (Stdlib.find(m.keys, m.len, k, mt.key(), free) >= 0) throw fail(path + e[2], "duplicate key");
                    Stdlib.mapPut(m, k, fromJson(e[1], mt.value(), path + e[2]), mt.key(), null, free);
                }
                return m;
            }
            case Type.TupleT tt -> {
                int n = tt.elems().size();
                if (!(j instanceof List<?> l)) throw fail(path, "expected an array of " + n + " items");
                if (l.size() != n) throw fail(path, "expected exactly " + n + " items, found " + l.size());
                Object[] vals = new Object[n];
                for (int i = 0; i < n; i++) vals[i] = fromJson(l.get(i), tt.elems().get(i), path + "[" + i + "]");
                return new StructV(vals);
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
