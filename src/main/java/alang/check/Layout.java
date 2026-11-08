package alang.check;

import alang.ast.Ast.EnumDecl;
import alang.ast.Ast.Field;
import alang.ast.Ast.Variant;
import alang.types.Type;

/**
 * Byte sizes and alignments of ALang values, following the C layout used by the C backend:
 * arrays are wrapped in structs, str/list carry a u32 length, enums carry a u32 tag
 * followed by a union of their payloads.
 */
public final class Layout {
    private Layout() {}

    public static long size(Type t) {
        return switch (t) {
            case Type.Prim p -> p.kind().size;
            case Type.Str s -> align(4 + (long) s.cap(), 4);
            case Type.ArrayT a -> size(a.elem()) * a.len();
            case Type.ListT l -> {
                long al = Math.max(4, align(l.elem()));
                yield align(align(4, align(l.elem())) + size(l.elem()) * l.cap(), al);
            }
            case Type.StructT s -> {
                long off = 0;
                long al = 1;
                for (Field f : s.decl().fields) {
                    long fa = align(f.type);
                    off = align(off, fa) + size(f.type);
                    al = Math.max(al, fa);
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
                off = align(off, fa) + size(t);
                al = Math.max(al, fa);
            }
            payload = Math.max(payload, off);
        }
        if (payload == 0) return 4;
        return align(align(4, al) + payload, al);
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
        return (off + al - 1) / al * al;
    }

    /** Bytes a parameter occupies in a frame: aggregates and `mut` parameters are passed by address. */
    public static long paramSize(Type t, boolean byRef) {
        if (byRef || Type.isAggregate(t)) return 8;
        return size(t);
    }
}
