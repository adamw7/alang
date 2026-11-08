package alang.interp;

import alang.ast.Ast.Field;
import alang.ast.Ast.Variant;
import alang.types.PrimKind;
import alang.types.Type;
import java.util.Arrays;

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
        if (v instanceof StrV s) return new StrV(s.b.clone(), s.len);
        if (v instanceof ListV l) {
            Object[] items = new Object[l.items.length];
            for (int i = 0; i < l.len; i++) items[i] = copy(l.items[i]);
            return new ListV(items, l.len);
        }
        if (v instanceof ArrV a) {
            Object[] items = new Object[a.items.length];
            for (int i = 0; i < items.length; i++) items[i] = copy(a.items[i]);
            return new ArrV(items);
        }
        if (v instanceof StructV s) {
            Object[] f = new Object[s.fields.length];
            for (int i = 0; i < f.length; i++) f[i] = copy(s.fields[i]);
            return new StructV(f);
        }
        if (v instanceof EnumV e) {
            Object[] p = new Object[e.payload.length];
            for (int i = 0; i < p.length; i++) p[i] = copy(e.payload[i]);
            return new EnumV(e.tag, p);
        }
        return v;
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

    /** A zero value of the given type (used for JSON decoding scaffolding). */
    public static Object zero(Type t) {
        return switch (t) {
            case Type.Prim p -> p.kind() == PrimKind.BOOL ? Boolean.FALSE : p.kind().isFloat ? (Object) 0.0 : (Object) 0L;
            case Type.Str s -> new StrV(new byte[s.cap()], 0);
            case Type.ListT l -> new ListV(new Object[l.cap()], 0);
            case Type.ArrayT a -> {
                Object[] items = new Object[a.len()];
                for (int i = 0; i < items.length; i++) items[i] = zero(a.elem());
                yield new ArrV(items);
            }
            case Type.StructT s -> {
                Object[] f = new Object[s.decl().fields.size()];
                for (int i = 0; i < f.length; i++) f[i] = zero(s.decl().fields.get(i).type);
                yield new StructV(f);
            }
            case Type.EnumT e -> {
                Variant v = e.decl().variants.get(0);
                Object[] p = new Object[v.payload.size()];
                for (int i = 0; i < p.length; i++) p[i] = zero(v.payload.get(i));
                yield new EnumV(0, p);
            }
            default -> null;
        };
    }

    public static boolean equal(Object a, Object b, Type t) {
        switch (t) {
            case Type.Prim p -> {
                if (p.kind().isFloat) return ((Double) a).doubleValue() == ((Double) b).doubleValue();
                return a.equals(b);
            }
            case Type.Str s -> {
                StrV x = (StrV) a;
                StrV y = (StrV) b;
                return x.len == y.len && Arrays.equals(x.b, 0, x.len, y.b, 0, y.len);
            }
            case Type.ListT l -> {
                ListV x = (ListV) a;
                ListV y = (ListV) b;
                if (x.len != y.len) return false;
                for (int i = 0; i < x.len; i++) {
                    if (!equal(x.items[i], y.items[i], l.elem())) return false;
                }
                return true;
            }
            case Type.ArrayT at -> {
                ArrV x = (ArrV) a;
                ArrV y = (ArrV) b;
                for (int i = 0; i < x.items.length; i++) {
                    if (!equal(x.items[i], y.items[i], at.elem())) return false;
                }
                return true;
            }
            case Type.StructT st -> {
                StructV x = (StructV) a;
                StructV y = (StructV) b;
                int i = 0;
                for (Field f : st.decl().fields) {
                    if (!equal(x.fields[i], y.fields[i], f.type)) return false;
                    i++;
                }
                return true;
            }
            case Type.EnumT et -> {
                EnumV x = (EnumV) a;
                EnumV y = (EnumV) b;
                if (x.tag != y.tag) return false;
                Variant v = et.decl().variants.get(x.tag);
                for (int i = 0; i < v.payload.size(); i++) {
                    if (!equal(x.payload[i], y.payload[i], v.payload.get(i))) return false;
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
