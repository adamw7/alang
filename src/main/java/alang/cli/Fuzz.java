package alang.cli;

import alang.ast.Ast.EnumDecl;
import alang.ast.Ast.Field;
import alang.ast.Ast.FnDecl;
import alang.ast.Ast.Program;
import alang.ast.Ast.Variant;
import alang.diag.Code;
import alang.diag.Source;
import alang.diag.Span;
import alang.interp.Interpreter;
import alang.interp.JsonValues;
import alang.interp.Trap;
import alang.types.PrimKind;
import alang.types.Type;
import alang.util.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * `alang fuzz`: calls every `tool fn`, and a `fn main(input: str[N])`, with arguments at the limits of
 * their types and `requires` clauses: empty and full strs of many shapes (distinct words, separators,
 * numbers, lines), empty and full lists, the smallest and largest numbers. A trap in such a call is a
 * trap the program's callers can hit with input the tool says it accepts; only the entry point's own
 * precondition is left out, which the caller breaks. The calls are the same on every run.
 */
final class Fuzz {
    private Fuzz() {}

    /** The most calls per entry point, and the most values tried for one parameter. */
    static final int MAX_CALLS = 200;
    private static final int MAX_VALUES = 24;

    /** A trap, and the arguments of the first call that hit it (for main: its input, as a JSON string). */
    record Finding(Trap trap, Object input) {}

    /** An entry point that was called: a tool, or main with its input. */
    record Entry(String name, boolean tool, int calls, List<Finding> findings) {}

    static List<Entry> run(Program program, Source src, long maxSteps) {
        List<Entry> out = new ArrayList<>();
        for (FnDecl f : program.itemsOf(FnDecl.class)) {
            if (f.tool) out.add(tool(f, program, src, maxSteps));
        }
        FnDecl main = program.function("main");
        if (main != null && !main.paramVars.isEmpty()) out.add(main(main, src, maxSteps));
        return out;
    }

    private static Entry tool(FnDecl f, Program program, Source src, long maxSteps) {
        Map<String, Object> schema = ToolDefinitions.inputSchema(f);
        Map<String, Object> props = map(schema.get("properties"));
        // The plainest arguments: those of the first example the tests give, else each parameter's first value.
        Map<String, Object> base = new LinkedHashMap<>();
        List<Object> examples = ToolExamples.of(f, program, src, maxSteps);
        Map<String, Object> example = examples.isEmpty() ? Map.of() : map(examples.get(0));
        List<List<Object>> values = new ArrayList<>();
        for (int i = 0; i < f.paramVars.size(); i++) {
            String name = f.paramVars.get(i).name;
            List<Object> vs = values(f.paramVars.get(i).type, map(props.get(name)));
            values.add(vs);
            base.put(name, example.containsKey(name) ? example.get(name) : vs.get(0));
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        calls.add(base);
        // One parameter at a time over its values, the others as in the plainest call.
        for (int i = 0; i < f.paramVars.size(); i++) {
            for (Object v : values.get(i)) {
                Map<String, Object> args = new LinkedHashMap<>(base);
                args.put(f.paramVars.get(i).name, v);
                calls.add(args);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> findings = new ArrayList<>();
        int n = 0;
        Set<String> done = new LinkedHashSet<>();
        for (Map<String, Object> args : calls) {
            if (n == MAX_CALLS) break;
            if (!done.add(Json.write(args))) continue;
            n++;
            Trap t = callTool(f, args, src, maxSteps);
            if (t != null && !ownPrecondition(t, f) && seen.add(key(t))) findings.add(new Finding(t, args));
        }
        return new Entry(f.name, true, n, findings);
    }

    private static Entry main(FnDecl main, Source src, long maxSteps) {
        int cap = ((Type.Str) main.paramVars.get(0).type).cap();
        Set<String> seen = new LinkedHashSet<>();
        List<Finding> findings = new ArrayList<>();
        List<String> inputs = texts(cap, 0);
        int n = 0;
        for (String input : inputs) {
            if (n == MAX_CALLS) break;
            n++;
            Interpreter in = new Interpreter(src, new CappedOutput(0), maxSteps);
            Trap t = null;
            try {
                in.runMain(main, input.getBytes(StandardCharsets.UTF_8));
            } catch (Trap trap) {
                t = trap;
            }
            if (t != null && !ownPrecondition(t, main) && seen.add(key(t))) findings.add(new Finding(t, input));
        }
        return new Entry("main", false, n, findings);
    }

    /** The trap of calling the tool with {@code args}, as `alang call` would, or null. */
    private static Trap callTool(FnDecl f, Map<String, Object> args, Source src, long maxSteps) {
        Object[] values = new Object[f.paramVars.size()];
        try {
            for (int i = 0; i < values.length; i++) {
                values[i] = JsonValues.fromJson(args.get(f.paramVars.get(i).name), f.paramVars.get(i).type,
                    f.paramVars.get(i).name);
            }
        } catch (JsonValues.DecodeException e) {
            // A value the generator made that the type does not take: no call.
            return null;
        }
        Interpreter in = new Interpreter(src, new CappedOutput(0), maxSteps);
        try {
            in.call(f, values, f.nameSpan);
            return null;
        } catch (Trap t) {
            return t;
        }
    }

    /** A `requires` of the entry point itself failed: the arguments were out of bounds, the caller's fault. */
    private static boolean ownPrecondition(Trap t, FnDecl f) {
        return t.code == Code.R0006 && t.span.equals(f.nameSpan);
    }

    private static String key(Trap t) {
        Span s = t.span;
        return t.code + " " + (s.file() == null ? "" : s.file().path) + ":" + s.line() + ":" + s.col();
    }

    // ------------------------------------------------------------------ values

    /** Values of type {@code t} within {@code schema}'s bounds, the plainest first, without repeats. */
    static List<Object> values(Type t, Map<String, Object> schema) {
        Set<Object> out = new LinkedHashSet<>();
        switch (t) {
            case Type.Prim p when p.kind() == PrimKind.BOOL -> {
                out.add(false);
                out.add(true);
            }
            case Type.Prim p when p.kind().isFloat -> {
                BigDecimal lo = decimal(schema, "minimum", "exclusiveMinimum");
                BigDecimal hi = decimal(schema, "maximum", "exclusiveMaximum");
                for (String v : new String[] {"0", "1", "-1", "0.5", "-2.25", "1000000000", "-1000000000", "1e-9"}) {
                    BigDecimal d = new BigDecimal(v);
                    if ((lo == null || d.compareTo(lo) > 0 || d.compareTo(lo) == 0 && schema.containsKey("minimum"))
                        && (hi == null || d.compareTo(hi) < 0 || d.compareTo(hi) == 0 && schema.containsKey("maximum"))) {
                        out.add(d);
                    }
                }
                if (lo != null && schema.containsKey("minimum")) out.add(lo);
                if (hi != null && schema.containsKey("maximum")) out.add(hi);
                if (out.isEmpty()) out.add(lo != null ? lo : BigDecimal.ZERO);
            }
            case Type.Prim p -> {
                BigInteger lo = integer(schema.get("minimum"), p.kind().min());
                BigInteger hi = integer(schema.get("maximum"), p.kind().max());
                BigInteger plain = lo.signum() > 0 ? lo : hi.signum() < 0 ? hi : BigInteger.ZERO;
                for (BigInteger v : new BigInteger[] {plain, BigInteger.ONE, BigInteger.TWO, BigInteger.ONE.negate(), lo, hi,
                    lo.add(BigInteger.ONE), hi.subtract(BigInteger.ONE), BigInteger.valueOf(100), BigInteger.valueOf(1000)}) {
                    if (v.compareTo(lo) >= 0 && v.compareTo(hi) <= 0) out.add(v);
                }
            }
            case Type.Str s -> {
                int max = Math.min(s.cap(), intOf(schema.get("maxLength"), s.cap()));
                int min = intOf(schema.get("minLength"), 0);
                for (String text : texts(max, min)) out.add(text);
            }
            case Type.ListT l -> listValues(out, l.elem(), map(schema.get("items")), intOf(schema.get("minItems"), 0),
                Math.min(l.cap(), intOf(schema.get("maxItems"), l.cap())));
            case Type.ArrayT a -> listValues(out, a.elem(), map(schema.get("items")), a.len(), a.len());
            case Type.StructT st -> {
                Map<String, Object> props = map(schema.get("properties"));
                Map<String, Object> plain = new LinkedHashMap<>();
                List<List<Object>> fields = new ArrayList<>();
                for (Field f : st.decl().fields) {
                    List<Object> vs = values(f.type, map(props.get(f.name)));
                    fields.add(vs);
                    plain.put(f.name, vs.get(0));
                }
                out.add(plain);
                for (int i = 0; i < fields.size(); i++) {
                    for (Object v : fields.get(i)) {
                        Map<String, Object> m = new LinkedHashMap<>(plain);
                        m.put(st.decl().fields.get(i).name, v);
                        out.add(m);
                    }
                }
            }
            case Type.SetT st -> {
                // Empty, then as many distinct values as fit, up to the capacity.
                List<Object> vs = values(st.elem(), JsonValues.schema(st.elem()));
                for (int n : new int[] {0, 1, Math.min(st.cap(), vs.size())}) out.add(new ArrayList<>(vs.subList(0, Math.min(n, vs.size()))));
            }
            case Type.MapT mt -> {
                List<Object> ks = values(mt.key(), JsonValues.schema(mt.key()));
                List<Object> vs = values(mt.value(), JsonValues.schema(mt.value()));
                for (int n : new int[] {0, 1, Math.min(mt.cap(), ks.size())}) {
                    n = Math.min(n, ks.size());
                    if (mt.key() instanceof Type.Str) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        for (int i = 0; i < n; i++) m.put((String) ks.get(i), vs.get(i % vs.size()));
                        out.add(m);
                    } else {
                        List<Object> pairs = new ArrayList<>();
                        for (int i = 0; i < n; i++) pairs.add(List.of(ks.get(i), vs.get(i % vs.size())));
                        out.add(pairs);
                    }
                }
            }
            case Type.TupleT tt -> {
                // As a struct: the plainest of each element, then each element in turn through its values.
                List<Object> items = list(schema.get("prefixItems"));
                List<Object> plain = new ArrayList<>();
                List<List<Object>> elems = new ArrayList<>();
                for (int i = 0; i < tt.elems().size(); i++) {
                    Type et = tt.elems().get(i);
                    List<Object> vs = values(et, items != null && i < items.size() ? map(items.get(i)) : JsonValues.schema(et));
                    elems.add(vs);
                    plain.add(vs.get(0));
                }
                out.add(plain);
                for (int i = 0; i < elems.size(); i++) {
                    for (Object v : elems.get(i)) {
                        List<Object> m = new ArrayList<>(plain);
                        m.set(i, v);
                        out.add(m);
                    }
                }
            }
            case Type.EnumT et -> {
                EnumDecl d = et.decl();
                for (Variant v : d.variants) {
                    if (v.payload.isEmpty()) {
                        out.add(v.name);
                    } else {
                        List<Object> payload = new ArrayList<>();
                        for (Type pt : v.payload) payload.add(values(pt, JsonValues.schema(pt)).get(0));
                        out.add(Map.of(v.name, payload));
                    }
                }
            }
            default -> out.add(null);
        }
        List<Object> list = new ArrayList<>(out);
        return list.size() > MAX_VALUES ? list.subList(0, MAX_VALUES) : list;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : null;
    }

    /** Lists (or arrays, when min == max) of min to max elements: the plainest, then full ones of many kinds. */
    private static void listValues(Set<Object> out, Type elem, Map<String, Object> items, int min, int max) {
        List<Object> vs = values(elem, items == null ? JsonValues.schema(elem) : items);
        out.add(repeat(vs.get(0), Math.max(min, 0)));
        if (min <= 1 && max >= 1) out.add(repeat(vs.get(0), 1));
        if (max > 0) {
            // As many as fit: each element the same, each different, and each of the largest kind.
            out.add(repeat(vs.get(0), max));
            List<Object> distinct = new ArrayList<>();
            for (int i = 0; i < max; i++) distinct.add(distinct(elem, items, vs, i));
            out.add(distinct);
            out.add(repeat(vs.get(vs.size() - 1), max));
            if (vs.size() > 1) out.add(repeat(vs.get(1), max));
        }
    }

    /** The {@code i}-th of a run of different values of type {@code elem}, where the type has that many. */
    private static Object distinct(Type elem, Map<String, Object> items, List<Object> vs, int i) {
        if (elem instanceof Type.Prim p && p.kind().isInt()) {
            BigInteger lo = integer(items == null ? null : items.get("minimum"), p.kind().min());
            BigInteger hi = integer(items == null ? null : items.get("maximum"), p.kind().max());
            BigInteger v = (lo.signum() > 0 ? lo : BigInteger.ZERO).add(BigInteger.valueOf(i));
            return v.compareTo(hi) <= 0 ? v : vs.get(i % vs.size());
        }
        if (elem instanceof Type.Str s) {
            String w = word(i);
            return w.length() <= s.cap() ? w : vs.get(i % vs.size());
        }
        return vs.get(i % vs.size());
    }

    private static List<Object> repeat(Object v, int n) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(v);
        return out;
    }

    /**
     * Texts of {@code min} to {@code max} ASCII bytes, the empty one (or the shortest) first: one long word, words
     * that all differ separated by spaces, commas, semicolons, tabs or lines, "name number" and "key: value"
     * lines, rows of fields, numbers, and separators alone.
     */
    static List<String> texts(int max, int min) {
        Set<String> out = new LinkedHashSet<>();
        out.add("a".repeat(Math.min(min, max)));
        out.add(fill(max, i -> word(i), " "));
        out.add("a".repeat(max));
        out.add(fill(max, i -> word(i), ","));
        out.add(fill(max, i -> word(i), "\n"));
        out.add(fill(max, i -> word(i) + " " + (i + 1), "\n"));
        out.add(fill(max, i -> word(i) + ": " + (i + 1), "\n"));
        out.add(fill(max, i -> word(3 * i) + "," + word(3 * i + 1) + "," + word(3 * i + 2), "\n"));
        // Lines that differ from one another: 1 to 6 fields, a number last, a first field that grows.
        out.add(fill(max, i -> fields(i, i % 6 + 1, ","), "\n"));
        out.add(fill(max, i -> fields(i, i % 6 + 1, " "), "\n"));
        out.add(fill(max, i -> word(2 * i) + "," + word(2 * i + 1) + "," + (i + 1), "\n"));
        out.add(fill(max, i -> "a".repeat(8 * (i + 1)) + "," + (i + 1), "\n"));
        out.add(fill(max, i -> String.valueOf(i + 1), " "));
        out.add(fill(max, i -> String.valueOf(i + 1), ","));
        out.add(fill(max, i -> (i % 2 == 0 ? "-" : "") + (i + 1) + "." + (i % 10), " "));
        out.add(fill(max, i -> word(i), "\t"));
        out.add(fill(max, i -> word(i), ";"));
        out.add(",".repeat(max));
        out.add("\n".repeat(max));
        out.add(" ".repeat(max));
        out.add(fill(max, i -> word(i).toUpperCase(Locale.ROOT) + " " + word(i), " "));
        List<String> list = new ArrayList<>();
        for (String s : out) {
            if (s.length() >= min && s.length() <= max) list.add(s);
        }
        if (list.isEmpty()) list.add("a".repeat(min));
        return list;
    }

    /** {@code n} different words joined by {@code sep}, the {@code i}-th line of a text. */
    private static String fields(int i, int n, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) sb.append(k == 0 ? "" : sep).append(word(6 * i + k));
        return sb.toString();
    }

    /** Pieces {@code piece(0)}, {@code piece(1)}, ... joined with {@code sep}, as many as fit in {@code max} bytes. */
    private static String fill(int max, IntFunction<String> piece, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            String p = (i == 0 ? "" : sep) + piece.apply(i);
            if (sb.length() + p.length() > max) break;
            sb.append(p);
        }
        return sb.toString();
    }

    /** a, b, ..., z, aa, ab, ...: the {@code i}-th word, all of them different. */
    static String word(int i) {
        StringBuilder sb = new StringBuilder();
        int n = i;
        do {
            sb.append((char) ('a' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.reverse().toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    private static BigInteger integer(Object bound, BigInteger otherwise) {
        if (bound instanceof BigInteger b) return b;
        if (bound instanceof Number n) return new BigDecimal(n.toString()).toBigInteger();
        return otherwise;
    }

    private static BigDecimal decimal(Map<String, Object> schema, String inclusive, String exclusive) {
        Object b = schema.containsKey(inclusive) ? schema.get(inclusive) : schema.get(exclusive);
        return b == null ? null : new BigDecimal(b.toString());
    }

    private static int intOf(Object bound, int otherwise) {
        return bound instanceof Number n ? (int) Math.max(0, Math.min(Integer.MAX_VALUE, n.longValue())) : otherwise;
    }
}
