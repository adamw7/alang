package alang.interp;

import alang.types.Type;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Runtime representation of ALang values inside the reference interpreter.
 * Integers of every width are {@link Long} (u64 uses the unsigned bit pattern), floats are
 * {@link Double} (f32 values are rounded to float precision), bools are {@link Boolean}.
 * Aggregates are mutable boxes that are copied whenever ALang semantics require a copy,
 * so the interpreter faithfully implements value semantics.
 */
public final class Values {
    private Values() {}

    public static final class StrV {
        public final byte[] b;
        public int len;

        public StrV(byte[] b, int len) {
            this.b = b;
            this.len = len;
        }
    }

    public static final class ListV {
        public final Object[] items;
        public int len;

        public ListV(Object[] items, int len) {
            this.items = items;
            this.len = len;
        }
    }

    public static final class ArrV {
        public final Object[] items;

        public ArrV(Object[] items) {
            this.items = items;
        }
    }

    public static final class StructV {
        public final Object[] fields;

        public StructV(Object[] fields) {
            this.fields = fields;
        }
    }

    public static final class EnumV {
        public final int tag;
        public final Object[] payload;

        public EnumV(int tag, Object[] payload) {
            this.tag = tag;
            this.payload = payload;
        }
    }

    /** A deep copy; primitives are immutable and returned as is. */
    public static Object copy(Object v) {
        return switch (v) {
            case StrV s -> new StrV(s.b.clone(), s.len);
            case ListV l -> new ListV(copy(l.items, l.len), l.len);
            case ArrV a -> new ArrV(copy(a.items, a.items.length));
            case StructV s -> new StructV(copy(s.fields, s.fields.length));
            case EnumV e -> new EnumV(e.tag, copy(e.payload, e.payload.length));
            default -> v;
        };
    }

    /** A copy of {@code items} with deep copies of its first {@code n} elements, and nothing after them. */
    private static Object[] copy(Object[] items, int n) {
        Object[] out = new Object[items.length];
        for (int i = 0; i < n; i++) out[i] = copy(items[i]);
        return out;
    }

    /** Adjusts the storage capacity of a str/list value to the target type (widening). */
    public static Object coerce(Object v, Type target) {
        if (target instanceof Type.Str s && v instanceof StrV sv && sv.b.length != s.cap()) {
            return new StrV(Arrays.copyOf(sv.b, s.cap()), sv.len);
        }
        if (target instanceof Type.ListT l && v instanceof ListV lv && lv.items.length != l.cap()) {
            return new ListV(Arrays.copyOf(lv.items, l.cap()), lv.len);
        }
        return v;
    }

    public static boolean equal(Object a, Object b, Type t) {
        return switch (t) {
            case Type.Prim p -> p.kind().isFloat ? ((Double) a).doubleValue() == ((Double) b).doubleValue() : a.equals(b);
            case Type.Str s -> {
                StrV x = (StrV) a;
                StrV y = (StrV) b;
                yield x.len == y.len && Arrays.equals(x.b, 0, x.len, y.b, 0, y.len);
            }
            case Type.ListT l -> ((ListV) a).len == ((ListV) b).len
                && allEqual(((ListV) a).items, ((ListV) b).items, ((ListV) a).len, i -> l.elem());
            case Type.ArrayT at -> allEqual(((ArrV) a).items, ((ArrV) b).items, ((ArrV) a).items.length, i -> at.elem());
            case Type.StructT st -> allEqual(((StructV) a).fields, ((StructV) b).fields, st.decl().fields.size(),
                i -> st.decl().fields.get(i).type);
            case Type.TupleT tt -> allEqual(((StructV) a).fields, ((StructV) b).fields, tt.elems().size(), i -> tt.elems().get(i));
            case Type.EnumT et -> {
                EnumV x = (EnumV) a;
                EnumV y = (EnumV) b;
                List<Type> payload = et.decl().variants.get(x.tag).payload;
                yield x.tag == y.tag && allEqual(x.payload, y.payload, payload.size(), payload::get);
            }
            default -> false;
        };
    }

    /** Whether the first {@code n} elements are equal, element {@code i} having type {@code type(i)}. */
    private static boolean allEqual(Object[] x, Object[] y, int n, IntFunction<Type> type) {
        for (int i = 0; i < n; i++) {
            if (!equal(x[i], y[i], type.apply(i))) return false;
        }
        return true;
    }
}
