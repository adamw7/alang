package alang.ast;

import alang.diag.Source;
import alang.diag.Span;
import alang.types.PrimKind;
import alang.types.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ALang abstract syntax tree. Nodes are created by {@code alang.parse.AstBuilder}
 * and annotated in place (types, resolved names, frame slots) by {@code alang.check.Checker}.
 */
public final class Ast {
    private Ast() {}

    // =================================================================== program

    /**
     * A program: the declarations of its main file, {@code source}, and of the files it imports. As the
     * parser returns it, it holds one file and that file's {@code imports}; the CLI reads the imported
     * files and joins them all into one program, whose {@code files} are the main file and then the
     * imported ones in the order they were read.
     */
    public static final class Program {
        public final Source source;
        public final List<Item> items;
        public final List<Import> imports;
        public final List<Source> files;

        public Program(Source source, List<Item> items, List<Import> imports) {
            this(source, items, imports, List.of(source));
        }

        public Program(Source source, List<Item> items, List<Import> imports, List<Source> files) {
            this.source = source;
            this.items = items;
            this.imports = imports;
            this.files = files;
        }

        public <T extends Item> List<T> itemsOf(Class<T> cls) {
            List<T> out = new ArrayList<>();
            for (Item i : items) {
                if (cls.isInstance(i)) out.add(cls.cast(i));
            }
            return out;
        }

        public FnDecl function(String name) {
            for (FnDecl f : itemsOf(FnDecl.class)) {
                if (f.name.equals(name)) return f;
            }
            return null;
        }
    }

    /** `import "path";` at the top of a file: {@code span} is the path's string literal. */
    public record Import(Span span, String path) {}

    public abstract static sealed class Item {
        public final Span span;
        public final String name;
        /** Text of the `///` doc comment directly above the item, or "". */
        public String doc = "";

        Item(Span span, String name) {
            this.span = span;
            this.name = name;
        }
    }

    /** Resolution state used by the checker for lazily resolved items. */
    public enum State { NEW, IN_PROGRESS, DONE }

    public static final class ConstDecl extends Item {
        public final TypeRef typeRef;
        public final Expr init;
        public Type type = Type.ERROR;
        /** The compile-time value (interpreter representation), or null on error. */
        public Object value;
        public State state = State.NEW;

        public ConstDecl(Span span, String name, TypeRef typeRef, Expr init) {
            super(span, name);
            this.typeRef = typeRef;
            this.init = init;
        }
    }

    public static final class StructDecl extends Item {
        public final List<Field> fields;
        public State state = State.NEW;

        public StructDecl(Span span, String name, List<Field> fields) {
            super(span, name);
            this.fields = fields;
        }

        public int fieldIndex(String name) {
            return fieldNames().indexOf(name);
        }

        public List<String> fieldNames() {
            return fields.stream().map(f -> f.name).toList();
        }
    }

    public static final class Field {
        public final Span span;
        public final String name;
        public final TypeRef typeRef;
        public Type type = Type.ERROR;
        /** Text of the `///` doc comment directly above the field, or "". */
        public String doc = "";

        public Field(Span span, String name, TypeRef typeRef) {
            this.span = span;
            this.name = name;
            this.typeRef = typeRef;
        }
    }

    public static final class EnumDecl extends Item {
        public final List<Variant> variants;
        public State state = State.NEW;

        public EnumDecl(Span span, String name, List<Variant> variants) {
            super(span, name);
            this.variants = variants;
        }

        public Variant variant(String name) {
            for (Variant v : variants) {
                if (v.name.equals(name)) return v;
            }
            return null;
        }
    }

    public static final class Variant {
        public final Span span;
        public final String name;
        public final int index;
        public final List<TypeRef> payloadRefs;
        public final List<Type> payload = new ArrayList<>();
        /** Text of the `///` doc comment directly above the variant, or "". */
        public String doc = "";

        public Variant(Span span, String name, int index, List<TypeRef> payloadRefs) {
            this.span = span;
            this.name = name;
            this.index = index;
            this.payloadRefs = payloadRefs;
        }
    }

    /** A function or test: something with a body and a stack frame. */
    public abstract static sealed class Routine extends Item {
        public final Block body;
        /** All locals of the frame (parameters first), each with a unique slot. */
        public final List<LocalVar> locals = new ArrayList<>();
        /** User functions called from this routine (including from contracts). */
        public final Set<FnDecl> callees = new LinkedHashSet<>();

        Routine(Span span, String name, Block body) {
            super(span, name);
            this.body = body;
        }
    }

    public static final class FnDecl extends Routine {
        public final Span nameSpan;
        public final boolean tool;
        public final List<Param> params;
        public final TypeRef retRef;
        public final List<Expr> requires;
        public final List<Expr> ensures;
        /** The source text of each `requires` clause on one line, for a tool's description. */
        public final List<String> requiresText;
        /** The source text of each `ensures` clause on one line. */
        public final List<String> ensuresText;
        public Type ret = Type.VOID;
        public final List<LocalVar> paramVars = new ArrayList<>();
        public LocalVar resultVar;

        public FnDecl(Span span, Span nameSpan, String name, boolean tool, List<Param> params, TypeRef retRef,
                      List<Expr> requires, List<Expr> ensures, List<String> requiresText, List<String> ensuresText,
                      Block body) {
            super(span, name, body);
            this.nameSpan = nameSpan;
            this.tool = tool;
            this.params = params;
            this.retRef = retRef;
            this.requires = requires;
            this.ensures = ensures;
            this.requiresText = requiresText;
            this.ensuresText = ensuresText;
        }
    }

    public static final class TestDecl extends Routine {
        public TestDecl(Span span, String description, Block body) {
            super(span, description, body);
        }
    }

    /** A parameter; {@code doc} is the text of the `///` doc comment directly above it, or "". */
    public record Param(Span span, String name, boolean mut, TypeRef typeRef, String doc) {}

    /** A local variable, parameter, loop variable, match binding, or `result`. */
    public static final class LocalVar {
        public final String name;
        public final Span span;
        public final Type type;
        public final boolean mutable;
        /** True for `mut` parameters: the slot holds a reference to the caller's storage. */
        public final boolean byRef;
        public final int slot;

        public LocalVar(String name, Span span, Type type, boolean mutable, boolean byRef, int slot) {
            this.name = name;
            this.span = span;
            this.type = type;
            this.mutable = mutable;
            this.byRef = byRef;
            this.slot = slot;
        }
    }

    // =================================================================== types

    public sealed interface TypeRef {
        Span span();
    }

    public record PrimRef(Span span, PrimKind kind) implements TypeRef {}

    public record StrRef(Span span, Expr cap) implements TypeRef {}

    public record ListRef(Span span, TypeRef elem, Expr cap) implements TypeRef {}

    public record ArrayRef(Span span, TypeRef elem, Expr len) implements TypeRef {}

    public record NamedRef(Span span, String name) implements TypeRef {}

    // =================================================================== statements

    public record Block(Span span, List<Stmt> stmts) {}

    public abstract static sealed class Stmt {
        public final Span span;

        Stmt(Span span) {
            this.span = span;
        }
    }

    public static final class Let extends Stmt {
        public final boolean mutable;
        public final String name;
        public final Span nameSpan;
        public final TypeRef typeRef;
        public final Expr init;
        public LocalVar var;

        public Let(Span span, boolean mutable, String name, Span nameSpan, TypeRef typeRef, Expr init) {
            super(span);
            this.mutable = mutable;
            this.name = name;
            this.nameSpan = nameSpan;
            this.typeRef = typeRef;
            this.init = init;
        }

        /**
         * Whether the initializer is built straight into the variable: split, words, join and replace take
         * the variable's type, and the C backend fills the variable itself rather than a temporary that is
         * then copied, so the memory bound counts the value once. The variable is new, so the arguments
         * cannot refer to it.
         */
        public boolean builtInPlace() {
            return init instanceof Call c && c.builtin != null && c.builtin.typedByTarget() && c.type.equals(var.type);
        }
    }

    public static final class Assign extends Stmt {
        public final Expr target;
        /** Null for plain `=`, otherwise the operator of a compound assignment (`+=` is ADD). */
        public final BinOp op;
        public final Expr value;

        public Assign(Span span, Expr target, BinOp op, Expr value) {
            super(span);
            this.target = target;
            this.op = op;
            this.value = value;
        }
    }

    public static final class If extends Stmt {
        public final List<Expr> conds;
        public final List<Block> blocks;
        public final Block elseBlock;

        public If(Span span, List<Expr> conds, List<Block> blocks, Block elseBlock) {
            super(span);
            this.conds = conds;
            this.blocks = blocks;
            this.elseBlock = elseBlock;
        }
    }

    public static final class While extends Stmt {
        public final Expr cond;
        public final Expr limit;
        public final Block body;

        public While(Span span, Expr cond, Expr limit, Block body) {
            super(span);
            this.cond = cond;
            this.limit = limit;
            this.body = body;
        }
    }

    /**
     * `for x in a..b` (to != null), `for x in collection` (to == null), or `for x in split(s, sep)` /
     * `for x in words(s)`, which goes through the pieces of s one at a time without building a list.
     */
    public static final class For extends Stmt {
        public final String name;
        public final Span nameSpan;
        public final Expr from;
        public final Expr to;
        public final Block body;
        public LocalVar var;
        /**
         * Set by the checker: whether the loop goes through a copy of what it iterates, taken before the
         * first iteration. Only a variable (the collection, or the text that split and words cut up) needs
         * one, and only when the body may change it; anything else is a value of its own already, and a
         * variable the body leaves alone holds the same value all along.
         */
        public boolean snapshot;

        public For(Span span, String name, Span nameSpan, Expr from, Expr to, Block body) {
            super(span);
            this.name = name;
            this.nameSpan = nameSpan;
            this.from = from;
            this.to = to;
            this.body = body;
        }

        public boolean isRange() {
            return to != null;
        }

        /** The split or words call whose pieces the loop goes through, or null for a range or a collection. */
        public Call pieces() {
            return to == null && from instanceof Call c && (c.builtin == Builtin.SPLIT || c.builtin == Builtin.WORDS) ? c : null;
        }

        /** What the loop takes its values from: the text of split and words, otherwise the collection. */
        public Expr source() {
            Call c = pieces();
            return c != null ? c.args.get(0).expr() : from;
        }
    }

    public static final class Match extends Stmt {
        public final Expr subject;
        public final List<Arm> arms;

        public Match(Span span, Expr subject, List<Arm> arms) {
            super(span);
            this.subject = subject;
            this.arms = arms;
        }
    }

    public record Arm(Pattern pattern, Block body) {}

    public static final class Break extends Stmt {
        public Break(Span span) {
            super(span);
        }
    }

    public static final class Continue extends Stmt {
        public Continue(Span span) {
            super(span);
        }
    }

    public static final class Return extends Stmt {
        public final Expr value;

        public Return(Span span, Expr value) {
            super(span);
            this.value = value;
        }
    }

    public static final class Assert extends Stmt {
        public final Expr cond;
        /** The bytes of the message literal, or null; like any str they need not be valid UTF-8. */
        public final byte[] message;

        public Assert(Span span, Expr cond, byte[] message) {
            super(span);
            this.cond = cond;
            this.message = message;
        }
    }

    public static final class ExprStmt extends Stmt {
        public final Expr expr;

        public ExprStmt(Span span, Expr expr) {
            super(span);
            this.expr = expr;
        }
    }

    // =================================================================== patterns

    public abstract static sealed class Pattern {
        public final Span span;

        Pattern(Span span) {
            this.span = span;
        }
    }

    public static final class VariantPat extends Pattern {
        public final String enumName;
        public final String variantName;
        /** Binding names (null entries for `_`), or null if the pattern has no parentheses. */
        public final List<String> bindings;
        public final List<Span> bindingSpans;
        public Variant variant;
        /** Locals for the bindings (null entries for `_`). */
        public final List<LocalVar> vars = new ArrayList<>();

        public VariantPat(Span span, String enumName, String variantName, List<String> bindings, List<Span> bindingSpans) {
            super(span);
            this.enumName = enumName;
            this.variantName = variantName;
            this.bindings = bindings;
            this.bindingSpans = bindingSpans;
        }
    }

    public static final class IntPat extends Pattern {
        public final BigInteger value;

        public IntPat(Span span, BigInteger value) {
            super(span);
            this.value = value;
        }
    }

    public static final class StrPat extends Pattern {
        public final byte[] value;

        public StrPat(Span span, byte[] value) {
            super(span);
            this.value = value;
        }
    }

    public static final class BoolPat extends Pattern {
        public final boolean value;

        public BoolPat(Span span, boolean value) {
            super(span);
            this.value = value;
        }
    }

    public static final class WildPat extends Pattern {
        public WildPat(Span span) {
            super(span);
        }
    }

    // =================================================================== expressions

    public enum BinOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
        BAND("&"), BOR("|"), BXOR("^"), SHL("<<"), SHR(">>"),
        EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        AND("and"), OR("or");

        public final String text;

        BinOp(String text) {
            this.text = text;
        }

        public boolean isArith() {
            return this == ADD || this == SUB || this == MUL || this == DIV || this == MOD;
        }

        public boolean isBitwise() {
            return this == BAND || this == BOR || this == BXOR;
        }

        public boolean isShift() {
            return this == SHL || this == SHR;
        }

        public boolean isOrdering() {
            return this == LT || this == LE || this == GT || this == GE;
        }

        public boolean isEquality() {
            return this == EQ || this == NE;
        }

        public boolean isLogic() {
            return this == AND || this == OR;
        }
    }

    public enum UnOp {
        NEG("-"), BNOT("~"), NOT("not ");

        public final String text;

        UnOp(String text) {
            this.text = text;
        }
    }

    public abstract static sealed class Expr {
        public final Span span;
        /** Set by the checker. */
        public Type type = Type.ERROR;

        Expr(Span span) {
            this.span = span;
        }

        /** Places evaluate to the storage they name; everything else builds a new value. */
        public boolean isPlace() {
            return this instanceof Name || this instanceof Index || (this instanceof FieldAccess f && f.variant == null);
        }
    }

    /** Integer or character literal. */
    public static final class IntLit extends Expr {
        public final BigInteger value;
        public final boolean isChar;

        public IntLit(Span span, BigInteger value, boolean isChar) {
            super(span);
            this.value = value;
            this.isChar = isChar;
        }

        /**
         * The value in a float context ({@code f32} or {@code f64}), rounded once to nearest-even.
         * Going through double first would round twice for f32.
         */
        public double floatValue(PrimKind k) {
            return k == PrimKind.F32 ? (double) value.floatValue() : value.doubleValue();
        }
    }

    public static final class FloatLit extends Expr {
        /** The literal rounded to f64. */
        public final double value;
        /** The literal rounded to f32 directly from its decimal text (not via f64, which would round twice). */
        public final float f32Value;

        public FloatLit(Span span, double value, float f32Value) {
            super(span);
            this.value = value;
            this.f32Value = f32Value;
        }

        /** The value for this literal's type ({@code f32} or {@code f64}). */
        public double valueAs(PrimKind k) {
            return k == PrimKind.F32 ? (double) f32Value : value;
        }
    }

    public static final class StrLit extends Expr {
        public final byte[] value;

        public StrLit(Span span, byte[] value) {
            super(span);
            this.value = value;
        }
    }

    public static final class BoolLit extends Expr {
        public final boolean value;

        public BoolLit(Span span, boolean value) {
            super(span);
            this.value = value;
        }
    }

    public static final class Name extends Expr {
        public final String name;
        public LocalVar local;
        public ConstDecl constant;

        public Name(Span span, String name) {
            super(span);
            this.name = name;
        }
    }

    /** `target.name`: a struct field, or a unit enum variant when target names an enum. */
    public static final class FieldAccess extends Expr {
        public final Expr target;
        public final String name;
        public final Span nameSpan;
        public int fieldIndex = -1;
        public Variant variant;

        public FieldAccess(Span span, Expr target, String name, Span nameSpan) {
            super(span);
            this.target = target;
            this.name = name;
            this.nameSpan = nameSpan;
        }
    }

    public static final class Index extends Expr {
        public final Expr target;
        public final Expr index;

        public Index(Span span, Expr target, Expr index) {
            super(span);
            this.target = target;
            this.index = index;
        }
    }

    public record Arg(Span span, boolean mut, Expr expr) {}

    /** A call of a user function or builtin. */
    public static final class Call extends Expr {
        public final String name;
        public final Span nameSpan;
        public final List<Arg> args;
        public FnDecl fn;
        public Builtin builtin;

        public Call(Span span, String name, Span nameSpan, List<Arg> args) {
            super(span);
            this.name = name;
            this.nameSpan = nameSpan;
            this.args = args;
        }

        public boolean hasMutArg() {
            return args.stream().anyMatch(Arg::mut);
        }
    }

    /** `Enum.Variant(args)`. */
    public static final class VariantCall extends Expr {
        public final String enumName;
        public final String variantName;
        public final List<Arg> args;
        public EnumDecl enumDecl;
        public Variant variant;

        public VariantCall(Span span, String enumName, String variantName, List<Arg> args) {
            super(span);
            this.enumName = enumName;
            this.variantName = variantName;
            this.args = args;
        }
    }

    public record FieldInit(Span span, String name, Expr value) {}

    /** `Point(x: 1, y: 2)`. */
    public static final class StructLit extends Expr {
        public final String name;
        public final List<FieldInit> fields;
        public StructDecl decl;
        /** For each field of the struct (declaration order), the index into {@link #fields}. */
        public int[] order;

        public StructLit(Span span, String name, List<FieldInit> fields) {
            super(span);
            this.name = name;
            this.fields = fields;
        }
    }

    /** `i64(x)`, `f64(n)`, ... */
    public static final class Convert extends Expr {
        public final PrimKind target;
        public final Expr arg;

        public Convert(Span span, PrimKind target, Expr arg) {
            super(span);
            this.target = target;
            this.arg = arg;
        }
    }

    /**
     * `str[N](s)` or `list[T; N](xs)`: the same text or elements with capacity N. Checked: a value longer
     * than N traps (R0004).
     */
    public static final class Resize extends Expr {
        public final TypeRef targetRef;
        public final Expr arg;

        public Resize(Span span, TypeRef targetRef, Expr arg) {
            super(span);
            this.targetRef = targetRef;
            this.arg = arg;
        }
    }

    public static final class Unary extends Expr {
        public final UnOp op;
        public final Expr operand;

        public Unary(Span span, UnOp op, Expr operand) {
            super(span);
            this.op = op;
            this.operand = operand;
        }
    }

    public static final class Binary extends Expr {
        public final BinOp op;
        public final Expr left;
        public final Expr right;

        public Binary(Span span, BinOp op, Expr left, Expr right) {
            super(span);
            this.op = op;
            this.left = left;
            this.right = right;
        }
    }

    public static final class ArrayLit extends Expr {
        public final List<Expr> elems;

        public ArrayLit(Span span, List<Expr> elems) {
            super(span);
            this.elems = elems;
        }
    }

    /** `[value; count]`. */
    public static final class Repeat extends Expr {
        public final Expr value;
        public final Expr count;
        public int n;

        public Repeat(Span span, Expr value, Expr count) {
            super(span);
            this.value = value;
            this.count = count;
        }
    }
}
