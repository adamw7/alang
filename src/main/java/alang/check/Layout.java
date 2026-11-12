package alang.check;

import alang.ast.Ast.EnumDecl;
import alang.ast.Ast.Field;
import alang.ast.Ast.Variant;
import alang.types.Type;

/**
 * Byte sizes and alignments of ALang values, following the C layout used by the C backend:
 * arrays are wrapped in structs, str/list carry a u32 length, enums carry a u32 tag
 * followed by a union of their payloads.
 *
 * <p>Sizes saturate at {@link Long#MAX_VALUE} instead of wrapping around, so a type that is too
 * large for any machine (e.g. three nested arrays of 2^20 elements) never reports a tiny or
 * negative size and can never slip under a {@code --max-stack} budget.
 */
public final class Layout {
    private Layout() {}

    /** a + b for sizes (both non-negative), saturating at Long.MAX_VALUE. */
    public static long add(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    /** a * b for sizes (both non-negative), saturating at Long.MAX_VALUE. */
    public static long mul(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    public static long size(Type t) {
        return switch (t) {
            case Type.Prim p -> p.kind().size;
            // C has no zero-length arrays, so the C backend gives the str[0] of "" a one-byte buffer.
            case Type.Str s -> align(add(4, Math.max(1, s.cap())), 4);
            case Type.ArrayT a -> mul(size(a.elem()), a.len());
            case Type.ListT l -> {
                long al = Math.max(4, align(l.elem()));
                yield align(add(align(4, align(l.elem())), mul(size(l.elem()), l.cap())), al);
            }
            case Type.StructT s -> {
                long off = 0;
                long al = 1;
                for (Field f : s.decl().fields) {
                    long fa = align(f.type);
                    off = add(align(off, fa), size(f.type));
                    al = Math.max(al, fa);
                }
                yield align(Math.max(off, 1), al);
            }
            case Type.SetT st -> size(new Type.ListT(st.elem(), st.cap()));
            case Type.MapT mt -> {
                // struct { uint32_t len; K k[N]; V v[N]; }
                long keys = add(align(4, align(mt.key())), mul(size(mt.key()), mt.cap()));
                long vals = add(align(keys, align(mt.value())), mul(size(mt.value()), mt.cap()));
                yield align(vals, align(t));
            }
            case Type.TupleT tt -> {
                long off = 0;
                long al = 1;
                for (Type e : tt.elems()) {
                    long ea = align(e);
                    off = add(align(off, ea), size(e));
                    al = Math.max(al, ea);
                }
                yield align(Math.max(off, 1), al);
            }
            case Type.EnumT e -> enumSize(e.decl());
            case Type.Void v -> 0;
            case Type.Error err -> 0;
        };
    }

    private static long enumSize(EnumDecl d) {
        long payload = 0;
        long al = 4;
        for (Variant v : d.variants) {
            long off = 0;
            for (Type t : v.payload) {
                long fa = align(t);
                off = add(align(off, fa), size(t));
                al = Math.max(al, fa);
            }
            payload = Math.max(payload, off);
        }
        if (payload == 0) return 4;
        return align(add(align(4, al), payload), al);
    }

    public static long align(Type t) {
        return switch (t) {
            case Type.Prim p -> p.kind().size;
            case Type.Str s -> 4;
            case Type.ArrayT a -> align(a.elem());
            case Type.ListT l -> Math.max(4, align(l.elem()));
            case Type.StructT s -> {
                long al = 1;
                for (Field f : s.decl().fields) al = Math.max(al, align(f.type));
                yield al;
            }
            case Type.TupleT tt -> {
                long al = 1;
                for (Type e : tt.elems()) al = Math.max(al, align(e));
                yield al;
            }
            case Type.SetT st -> Math.max(4, align(st.elem()));
            case Type.MapT mt -> Math.max(4, Math.max(align(mt.key()), align(mt.value())));
            case Type.EnumT e -> {
                long al = 4;
                for (Variant v : e.decl().variants) {
                    for (Type p : v.payload) al = Math.max(al, align(p));
                }
                yield al;
            }
            case Type.Void v -> 1;
            case Type.Error err -> 1;
        };
    }

    static long align(long off, long al) {
        if (off > Long.MAX_VALUE - (al - 1)) return Long.MAX_VALUE;
        return (off + al - 1) / al * al;
    }

    /** Bytes a parameter occupies in a frame: aggregates and `mut` parameters are passed by address. */
    public static long paramSize(Type t, boolean byRef) {
        if (byRef || Type.isAggregate(t)) return 8;
        return size(t);
    }
}
