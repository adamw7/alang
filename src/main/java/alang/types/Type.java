package alang.types;

import alang.ast.Ast;

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

    /** Text of up to {@code cap} bytes (UTF-8), stored inline. */
    record Str(int cap) implements Type {
        @Override
        public String toString() {
            return "str[" + cap + "]";
        }
    }

    /** Between 0 and {@code cap} elements, stored inline. */
    record ListT(Type elem, int cap) implements Type {
        @Override
        public String toString() {
            return "list[" + elem + "; " + cap + "]";
        }
    }

    /** Exactly {@code len} elements, stored inline. */
    record ArrayT(Type elem, int len) implements Type {
        @Override
        public String toString() {
            return "[" + elem + "; " + len + "]";
        }
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
        if (from instanceof Str a && to instanceof Str b) return a.cap() <= b.cap();
        if (from instanceof ListT a && to instanceof ListT b) return a.elem().equals(b.elem()) && a.cap() <= b.cap();
        return false;
    }

    /** Can values of these two types be compared with == and != ? */
    static boolean comparable(Type a, Type b) {
        if (isError(a) || isError(b)) return true;
        if (a instanceof Void || b instanceof Void) return false;
        if (a.equals(b)) return true;
        if (a instanceof Str && b instanceof Str) return true;
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
