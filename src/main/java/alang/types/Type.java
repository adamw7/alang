package alang.types;

import alang.ast.Ast;
import java.util.List;
import java.util.Map;

/**
 * Semantic types. Every type has a size known at compile time: there are no pointers,
 * references, or dynamically sized values.
 */
public sealed interface Type {

    record Prim(PrimKind kind) implements Type {
        @Override
        public String toString() {
            return kind.name;
        }
    }

    /**
     * Text of up to {@code cap} bytes (UTF-8), stored inline. A negative {@code cap} is a `_` of a parameter's
     * type, any capacity (see {@link #anyCapacity}).
     */
    record Str(int cap) implements Type {
        @Override
        public String toString() {
            return "str[" + capText(cap) + "]";
        }
    }

    /** Between 0 and {@code cap} elements, stored inline; a negative {@code cap} is a `_`, as for {@link Str}. */
    record ListT(Type elem, int cap) implements Type {
        @Override
        public String toString() {
            return "list[" + elem + "; " + capText(cap) + "]";
        }
    }

    /** Exactly {@code len} elements, stored inline; a negative {@code len} is a `_`, as for {@link Str}. */
    record ArrayT(Type elem, int len) implements Type {
        @Override
        public String toString() {
            return "[" + elem + "; " + capText(len) + "]";
        }
    }

    /**
     * Up to {@code cap} entries from keys to values, stored inline and always in the order of their keys (the order
     * sort gives), so that iteration, printing and equality never depend on how the entries were added.
     */
    record MapT(Type key, Type value, int cap) implements Type {
        @Override
        public String toString() {
            return "map[" + key + ", " + value + "; " + capText(cap) + "]";
        }
    }

    /** Up to {@code cap} distinct values, stored inline in order. */
    record SetT(Type elem, int cap) implements Type {
        @Override
        public String toString() {
            return "set[" + elem + "; " + capText(cap) + "]";
        }
    }

    /** A tuple of two or more values of the given types: (i64, str[8]). Its elements are read as t.0, t.1, ... */
    record TupleT(List<Type> elems) implements Type {
        @Override
        public String toString() {
            return "(" + String.join(", ", elems.stream().map(Type::toString).toList()) + ")";
        }
    }

    /**
     * The capacity that the {@code i}-th `_` (from 0) of a function's parameter types stands for: inside the
     * function it is unknown, and each call fixes it.
     */
    static int anyCapacity(int i) {
        return -1 - i;
    }

    private static String capText(int cap) {
        return cap < 0 ? "_" : String.valueOf(cap);
    }

    /** Whether a `_` capacity or length occurs anywhere in {@code t}. */
    static boolean hasAnyCapacity(Type t) {
        return switch (t) {
            case Str s -> s.cap() < 0;
            case ListT l -> l.cap() < 0 || hasAnyCapacity(l.elem());
            case ArrayT a -> a.len() < 0 || hasAnyCapacity(a.elem());
            case TupleT tt -> tt.elems().stream().anyMatch(Type::hasAnyCapacity);
            case MapT m -> m.cap() < 0 || hasAnyCapacity(m.key()) || hasAnyCapacity(m.value());
            case SetT s -> s.cap() < 0 || hasAnyCapacity(s.elem());
            default -> false;
        };
    }

    /** {@code t} with the {@code i}-th `_` replaced by {@code caps.get(i)}. */
    static Type substitute(Type t, List<Integer> caps) {
        return switch (t) {
            case Str s -> s.cap() < 0 ? new Str(caps.get(-1 - s.cap())) : s;
            case ListT l -> new ListT(substitute(l.elem(), caps), l.cap() < 0 ? caps.get(-1 - l.cap()) : l.cap());
            case ArrayT a -> new ArrayT(substitute(a.elem(), caps), a.len() < 0 ? caps.get(-1 - a.len()) : a.len());
            case TupleT tt -> new TupleT(tt.elems().stream().map(e -> substitute(e, caps)).toList());
            case MapT m -> new MapT(substitute(m.key(), caps), substitute(m.value(), caps), m.cap() < 0 ? caps.get(-1 - m.cap()) : m.cap());
            case SetT s -> new SetT(substitute(s.elem(), caps), s.cap() < 0 ? caps.get(-1 - s.cap()) : s.cap());
            default -> t;
        };
    }

    /**
     * Matches the type of an argument against that of a parameter with `_`s, and records in {@code caps} what
     * each `_` stands for. A `mut` argument must have exactly the parameter's type, apart from its `_`s; a
     * read-only one may also have a smaller capacity where the parameter's is a number, as for {@link #assignable}.
     * The elements of a list or array always have exactly the element type.
     */
    static boolean bind(Type param, Type arg, boolean exact, Map<Integer, Integer> caps) {
        if (isError(param) || isError(arg)) return true;
        if (!hasAnyCapacity(param)) return exact ? param.equals(arg) : assignable(arg, param);
        return switch (param) {
            case Str p when arg instanceof Str a -> {
                caps.put(-1 - p.cap(), a.cap());
                yield true;
            }
            case ListT p when arg instanceof ListT a -> bind(p.elem(), a.elem(), true, caps) && bindCap(p.cap(), a.cap(), exact, caps);
            case ArrayT p when arg instanceof ArrayT a -> bind(p.elem(), a.elem(), true, caps) && bindCap(p.len(), a.len(), true, caps);
            case MapT p when arg instanceof MapT a -> bind(p.key(), a.key(), true, caps) && bind(p.value(), a.value(), true, caps)
                && bindCap(p.cap(), a.cap(), exact, caps);
            case SetT p when arg instanceof SetT a -> bind(p.elem(), a.elem(), true, caps) && bindCap(p.cap(), a.cap(), exact, caps);
            case TupleT p when arg instanceof TupleT a && p.elems().size() == a.elems().size() -> {
                for (int i = 0; i < p.elems().size(); i++) {
                    if (!bind(p.elems().get(i), a.elems().get(i), true, caps)) yield false;
                }
                yield true;
            }
            default -> false;
        };
    }

    private static boolean bindCap(int param, int arg, boolean exact, Map<Integer, Integer> caps) {
        if (param < 0) {
            caps.put(-1 - param, arg);
            return true;
        }
        return exact ? param == arg : arg >= 0 && arg <= param;
    }

    record StructT(Ast.StructDecl decl) implements Type {
        @Override
        public String toString() {
            return decl.name;
        }
    }

    record EnumT(Ast.EnumDecl decl) implements Type {
        @Override
        public String toString() {
            return decl.name;
        }
    }

    record Void() implements Type {
        @Override
        public String toString() {
            return "void";
        }
    }

    /** Produced after an error was reported; compatible with everything to avoid cascades. */
    record Error() implements Type {
        @Override
        public String toString() {
            return "<error>";
        }
    }

    Type BOOL = new Prim(PrimKind.BOOL);
    Type I8 = new Prim(PrimKind.I8);
    Type I16 = new Prim(PrimKind.I16);
    Type I32 = new Prim(PrimKind.I32);
    Type I64 = new Prim(PrimKind.I64);
    Type U8 = new Prim(PrimKind.U8);
    Type U16 = new Prim(PrimKind.U16);
    Type U32 = new Prim(PrimKind.U32);
    Type U64 = new Prim(PrimKind.U64);
    Type F32 = new Prim(PrimKind.F32);
    Type F64 = new Prim(PrimKind.F64);
    Type VOID = new Void();
    Type ERROR = new Error();

    static Type prim(PrimKind k) {
        return new Prim(k);
    }

    static boolean isInt(Type t) {
        return t instanceof Prim p && p.kind().isInt();
    }

    static boolean isFloat(Type t) {
        return t instanceof Prim p && p.kind().isFloat;
    }

    static boolean isNumeric(Type t) {
        return t instanceof Prim p && p.kind().isNumeric();
    }

    static boolean isBool(Type t) {
        return t instanceof Prim p && p.kind() == PrimKind.BOOL;
    }

    static boolean isError(Type t) {
        return t instanceof Error;
    }

    static boolean isAggregate(Type t) {
        return !(t instanceof Prim) && !(t instanceof Void) && !(t instanceof Error);
    }

    static PrimKind kind(Type t) {
        return t instanceof Prim p ? p.kind() : null;
    }

    /** Can a value of type {@code from} be stored where {@code to} is expected? */
    static boolean assignable(Type from, Type to) {
        if (isError(from) || isError(to)) return true;
        if (from.equals(to)) return true;
        if (from instanceof Str a && to instanceof Str b) return fits(a.cap(), b.cap());
        if (from instanceof ListT a && to instanceof ListT b) return a.elem().equals(b.elem()) && fits(a.cap(), b.cap());
        if (from instanceof MapT a && to instanceof MapT b) {
            return a.key().equals(b.key()) && a.value().equals(b.value()) && fits(a.cap(), b.cap());
        }
        if (from instanceof SetT a && to instanceof SetT b) return a.elem().equals(b.elem()) && fits(a.cap(), b.cap());
        return false;
    }

    /**
     * Whether every value of capacity {@code from} fits capacity {@code to}. A `_` capacity is unknown: only
     * an empty value (capacity 0) is sure to fit it, and it fits nothing but itself.
     */
    private static boolean fits(int from, int to) {
        if (to < 0) return from == to || from == 0;
        return from >= 0 && from <= to;
    }

    /** Can values of these two types be compared with == and != ? */
    static boolean comparable(Type a, Type b) {
        if (isError(a) || isError(b)) return true;
        if (a instanceof Void || b instanceof Void) return false;
        if (a.equals(b)) return true;
        if (a instanceof Str && b instanceof Str) return true;
        if (a instanceof MapT ma && b instanceof MapT mb) return ma.key().equals(mb.key()) && ma.value().equals(mb.value());
        if (a instanceof SetT sa && b instanceof SetT sb) return sa.elem().equals(sb.elem());
        return a instanceof ListT la && b instanceof ListT lb && la.elem().equals(lb.elem());
    }

    /** Element type when indexing or iterating, or null. */
    static Type elementOf(Type t) {
        if (t instanceof ArrayT a) return a.elem();
        if (t instanceof ListT l) return l.elem();
        if (t instanceof Str) return U8;
        return null;
    }
}
