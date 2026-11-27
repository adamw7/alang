package alang.ast;

import alang.diag.*;
import alang.types.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Supplier;

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
        /**
         * For each file that imports with `as`: its aliases and the scope each names. A file imported with `as` has a
         * scope of its own (shared with the files it imports without `as`), whose names do not clash with others'.
         */
        public final Map<Source, Map<String, Integer>> aliases;
        /** The scope of each file's declarations; a file not listed is in scope 0. */
        public final Map<Source, Integer> scopes;
        /**
         * The functions the program uses as values (stored, returned, or passed to a function that takes any function
         * of a type), in the order the checker met them: each instance with known types. A function value is one of them.
         */
        public final List<FnDecl> functionValues = new ArrayList<>();

        public Program(Source source, List<Item> items, List<Import> imports) {
            this(source, items, imports, List.of(source), Map.of(), Map.of());
        }

        public Program(Source source, List<Item> items, List<Import> imports, List<Source> files,
                       Map<Source, Map<String, Integer>> aliases, Map<Source, Integer> scopes) {
            this.source = source;
            this.items = items;
            this.imports = imports;
            this.files = files;
            this.aliases = aliases;
            this.scopes = scopes;
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

    /**
     * `import "path";` or `import "path" as alias;` at the top of a file: {@code span} is the path's string literal;
     * {@code alias} is null without `as`.
     */
    public record Import(Span span, String path, String alias, Span aliasSpan) {}

    public abstract static sealed class Item {
        public final Span span;
        public final String name;
        /** Text of the `///` doc comment directly above the item, or "". */
        public String doc = "";
        /** The scope of its names: 0 for the main file and what it imports without `as` (see {@link Program#aliases}). */
        public int scope;
        /** A declaration every program has, Option and Result: seen from every scope, and reserved. */
        public boolean builtin;

        Item(Span span, String name) {
            this.span = span;
            this.name = name;
        }
    }

    /**
     * The type parameters of a struct or enum, `struct Pair<A, B>`, and what makes it types. The declaration as written
     * has fields (or payloads) of type parameters; each list of types its parameters stand for, Pair<i64, bool>, is an
     * instance: a declaration of its own, made once, with those types in place, which every later phase sees as an
     * ordinary struct or enum. An instance knows its origin and its type arguments.
     */
    public static final class Generics {
        public final List<String> params;
        public final List<Span> paramSpans;
        /** For an instance: the declaration it is made from, and the types its parameters stand for. */
        public Item origin;
        public List<Type> args = List.of();
        /** For a declaration with parameters: its instances, by their type arguments, in the order they were made. */
        public final Map<List<Type>, Item> instances = new LinkedHashMap<>();
        /** Set by the checker on a declaration with parameters: its instance for the given types, or null if it has none. */
        public java.util.function.Function<List<Type>, Item> instantiate;

        public Generics(List<String> params, List<Span> paramSpans) {
            this.params = params;
            this.paramSpans = paramSpans;
        }

        /** Whether the declaration as written has type parameters (an instance has none left). */
        public boolean generic() {
            return !params.isEmpty() && origin == null;
        }
    }

    /** The generics of a struct or enum declaration. */
    public static Generics generics(Item d) {
        return d instanceof StructDecl s ? s.generics : ((EnumDecl) d).generics;
    }

    /** The declaration an instance is made from, or the declaration itself. */
    public static Item origin(Item d) {
        Generics g = generics(d);
        return g.origin != null ? g.origin : d;
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
        /**
         * For the default value of a parameter, `fn f(n: i64 = 3)`: the function. The constant is then named after the
         * parameter, cannot be named in code, and is what a call that leaves the argument out passes.
         */
        public FnDecl defaultOf;
        /** For the default value of a field, `struct P { n: i64 = 3 }`: the struct; the constant is named after the field. */
        public StructDecl fieldOf;

        public ConstDecl(Span span, String name, TypeRef typeRef, Expr init) {
            super(span, name);
            this.typeRef = typeRef;
            this.init = init;
        }
    }

    /**
     * `type Grid = [[i64; 8]; 8];`: another name for a type. With type parameters, `type Pair<T> = (T, T);`, each use
     * gives their types, Pair<i64>, which stand for them in the type it names.
     */
    public static final class TypeAlias extends Item {
        public final Generics generics;
        public final TypeRef typeRef;

        public TypeAlias(Span span, String name, Generics generics, TypeRef typeRef) {
            super(span, name);
            this.generics = generics;
            this.typeRef = typeRef;
        }
    }

    public static final class StructDecl extends Item {
        public final List<Field> fields;
        public State state = State.NEW;
        /** The type parameters of `struct Pair<A, B>`, how a struct and an enum with them become types. */
        public final Generics generics;

        public StructDecl(Span span, String name, List<Field> fields) {
            this(span, name, fields, new Generics(List.of(), List.of()));
        }

        public StructDecl(Span span, String name, List<Field> fields, Generics generics) {
            super(span, name);
            this.fields = fields;
            this.generics = generics;
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
        /** The default value as written, `retries: i64 = 3`, or null. */
        public Expr def;
        /** Set by the checker: the constant that holds the default value, or null. */
        public ConstDecl defConst;

        public Field(Span span, String name, TypeRef typeRef) {
            this.span = span;
            this.name = name;
            this.typeRef = typeRef;
        }
    }

    public static final class EnumDecl extends Item {
        public final List<Variant> variants;
        public State state = State.NEW;
        /** The type parameters of `enum Maybe<T>`: see {@link Generics}. */
        public final Generics generics;

        public EnumDecl(Span span, String name, List<Variant> variants) {
            this(span, name, variants, new Generics(List.of(), List.of()));
        }

        public EnumDecl(Span span, String name, List<Variant> variants, Generics generics) {
            super(span, name);
            this.variants = variants;
            this.generics = generics;
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
        /** Functions this routine passes as values, to a parameter of a function type. */
        public final Set<FnDecl> fnValues = new LinkedHashSet<>();
        /**
         * The types of the function values this routine calls: each such call may call any function that the program uses
         * as a value ({@link Program#functionValues}) and has that type.
         */
        public final Set<Type.FnT> dynamicCalls = new LinkedHashSet<>();

        Routine(Span span, String name, Block body) {
            super(span, name);
            this.body = body;
        }
    }

    public static final class FnDecl extends Routine {
        public final Span nameSpan;
        public final boolean tool;
        /**
         * `host fn price(sku: str[16]) -> i64;`: declared without a body, as the program's host answers each call; its
         * body is an empty block. A test may bind it to a function of the program instead ({@link TestDecl#bindings}).
         */
        public boolean host;
        /** The names of `fn f<T, U>`'s type parameters, and where each is declared. */
        public final List<String> typeParams;
        public final List<Span> typeParamSpans;
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
        /** For each parameter, the constant holding its default value, or null for one without a default. */
        public final List<ConstDecl> defaults = new ArrayList<>();
        public LocalVar resultVar;
        /**
         * For a function with a `_` capacity in a parameter's type, such as fn total(xs: list[i64; _]): builds a
         * fresh, unchecked copy of it, which the checker turns into an instance for the capacities of a call.
         * Null for every other function.
         */
        public Supplier<FnDecl> copy;
        /**
         * For an instance: the function it is a copy of, the capacities its `_`s stand for, the types its type
         * parameters stand for, and for each parameter of a function type the function it is given (null for others).
         */
        public FnDecl origin;
        public List<Integer> capacities = List.of();
        /**
         * `fn f<N>(xs: [i64; N])`: the names among {@code <...>} that stand for a capacity rather than a type, moved out
         * of {@link #typeParams} by the checker, and the index of the `_` of {@link Type#anyCapacity} each stands for.
         */
        public final List<String> capacityParams = new ArrayList<>();
        public final List<Span> capacityParamSpans = new ArrayList<>();
        public final Map<String, Integer> capacityIndex = new HashMap<>();
        public List<Type> typeArgs = List.of();
        public List<FnDecl> fnArgs = List.of();
        /**
         * For a function with `_` capacities, type parameters or parameters of a function type: its instances, by
         * what they stand for, in the order they were made.
         */
        public final Map<List<Object>, FnDecl> instances = new LinkedHashMap<>();

        /** The type of the function as a value: its parameters' types and its result's. */
        public Type.FnT fnType() {
            List<Type> ps = new ArrayList<>();
            for (LocalVar v : paramVars) ps.add(v.type);
            return new Type.FnT(ps, ret);
        }

        /** Whether the function takes any capacity somewhere: it runs and compiles only as its instances. */
        public boolean generic() {
            return copy != null && origin == null;
        }

        public FnDecl(Span span, Span nameSpan, String name, boolean tool, List<String> typeParams, List<Span> typeParamSpans,
                      List<Param> params, TypeRef retRef, List<Expr> requires, List<Expr> ensures, List<String> requiresText,
                      List<String> ensuresText, Block body) {
            super(span, name, body);
            this.nameSpan = nameSpan;
            this.tool = tool;
            this.typeParams = typeParams;
            this.typeParamSpans = typeParamSpans;
            this.params = params;
            this.retRef = retRef;
            this.requires = requires;
            this.ensures = ensures;
            this.requiresText = requiresText;
            this.ensuresText = ensuresText;
        }
    }

    /** `price = fixed_price` in `test "..." with price = fixed_price { }`: a host fn, and the function a test runs for it. */
    public record HostBinding(String host, Span hostSpan, String fn, Span fnSpan) {}

    public static final class TestDecl extends Routine {
        /**
         * `test "..." traps R0006 { }`: the trap the body must stop with for the test to pass, and where the code is
         * written; null for a test that passes when the body runs to its end.
         */
        public final alang.diag.Code expectedTrap;
        public final Span trapSpan;
        /** `test "..." with price = fixed_price { }`: each host fn the test binds, to a function of the program. */
        public final List<HostBinding> bindings = new ArrayList<>();
        /** Set by the checker: for each bound host fn, the function its calls run in this test. */
        public final Map<FnDecl, FnDecl> hosts = new LinkedHashMap<>();

        public TestDecl(Span span, String description, Block body) {
            this(span, description, body, null, null);
        }

        public TestDecl(Span span, String description, Block body, alang.diag.Code expectedTrap, Span trapSpan) {
            super(span, description, body);
            this.expectedTrap = expectedTrap;
            this.trapSpan = trapSpan;
        }
    }

    /**
     * A parameter; {@code doc} is the text of the `///` doc comment directly above it, or "", and {@code def} the
     * constant expression of `name: T = value`, or null.
     */
    public record Param(Span span, String name, boolean mut, TypeRef typeRef, String doc, Expr def) {}

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

    /** {@code cap} is null for `_`: any capacity, in the type of a function parameter. */
    public record StrRef(Span span, Expr cap) implements TypeRef {}

    /** {@code cap} is null for `_`: any capacity, in the type of a function parameter. */
    public record ListRef(Span span, TypeRef elem, Expr cap) implements TypeRef {}

    /** {@code len} is null for `_`: any length, in the type of a function parameter. */
    public record ArrayRef(Span span, TypeRef elem, Expr len) implements TypeRef {}

    /** A struct or enum by name, with its type arguments, Pair<i64, bool>; {@code qualifier} is the alias of `ns.Name`, or null. */
    public record NamedRef(Span span, String name, String qualifier, List<TypeRef> args) implements TypeRef {
        public NamedRef(Span span, String name, String qualifier) {
            this(span, name, qualifier, List.of());
        }
    }

    /** {@code cap} is null for `_`: any capacity, in the type of a function parameter. */
    public record MapRef(Span span, TypeRef key, TypeRef value, Expr cap) implements TypeRef {}

    /** {@code cap} is null for `_`: any capacity, in the type of a function parameter. */
    public record SetRef(Span span, TypeRef elem, Expr cap) implements TypeRef {}

    /** fn(T1, T2) -> R: a function type, for a parameter; {@code ret} is null for none. */
    public record FnRef(Span span, List<TypeRef> params, TypeRef ret) implements TypeRef {}

    /** (T1, T2, ...): a tuple type. */
    public record TupleRef(Span span, List<TypeRef> elems) implements TypeRef {}

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

    /**
     * `let (a, b): (T1, T2) = e;` or `let Point(x: px, y: _): Point = p;`: a value taken apart by a pattern that always
     * matches, each name of it a new variable.
     */
    public static final class Destructure extends Stmt {
        public final boolean mutable;
        public final Pattern pattern;
        public final TypeRef typeRef;
        public final Expr init;
        /**
         * `let P: T = e else { ... };`: the block that runs when P does not match, which must leave (return, break,
         * continue or panic); null for a let whose pattern always matches.
         */
        public final Block elseBlock;
        /**
         * Whether it is the first statement of the body of `for P in xs`, made by the parser: it takes apart the loop's
         * hidden variable, init, whose type it has, and P must always match.
         */
        public final boolean loopItem;

        public Destructure(Span span, boolean mutable, Pattern pattern, TypeRef typeRef, Expr init) {
            this(span, mutable, pattern, typeRef, init, null, false);
        }

        public Destructure(Span span, boolean mutable, Pattern pattern, TypeRef typeRef, Expr init, Block elseBlock,
                           boolean loopItem) {
            super(span);
            this.mutable = mutable;
            this.pattern = pattern;
            this.typeRef = typeRef;
            this.init = init;
            this.elseBlock = elseBlock;
            this.loopItem = loopItem;
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

    /** A `for` or `while` loop, which `outer: for ...` may label for `break outer;` and `continue outer;`. */
    public abstract static sealed class Loop extends Stmt {
        public String label;
        public Span labelSpan;
        /** For a loop that gives a value ({@link LoopExpr}): the type of that value, set by the checker; null for others. */
        public Type valueType;

        Loop(Span span) {
            super(span);
        }
    }

    /**
     * `while cond limit n { }`, or `while let P = cond limit n { }`: with a pattern, cond is the value it is matched
     * against before each iteration, which runs with the pattern's names bound while it matches.
     */
    public static final class While extends Loop {
        public final Expr cond;
        public final Expr limit;
        public final Block body;
        /** The pattern of `while let`, or null. */
        public final Pattern pattern;
        /**
         * `while let P = e and c limit n`: the condition c, which may use the names P binds; the loop runs while P
         * matches and c holds. Null without one.
         */
        public Expr guard;

        public While(Span span, Expr cond, Expr limit, Block body) {
            this(span, null, cond, limit, body);
        }

        public While(Span span, Pattern pattern, Expr cond, Expr limit, Block body) {
            super(span);
            this.pattern = pattern;
            this.cond = cond;
            this.limit = limit;
            this.body = body;
        }
    }

    /**
     * `for x in a..b` (to != null), `for x in collection` (to == null), or `for x in split(s, sep)` /
     * `for x in words(s)` / `for c in chars(s)`, which go through the pieces (or code points) of s one at a time
     * without building a list.
     */
    public static final class For extends Loop {
        public final String name;
        public final Span nameSpan;
        /** `for i, x in xs`: the name of the index, i; null without one. */
        public final String indexName;
        public final Span indexSpan;
        public final Expr from;
        public final Expr to;
        /** `a..=b`: the range includes b. */
        public final boolean inclusive;
        /** `a..b step k`: the step, or null for 1. */
        public final Expr step;
        /** `rev`: the same values, last first. */
        public final boolean rev;
        public final Block body;
        public LocalVar var;
        public LocalVar indexVar;
        /**
         * Set by the checker: whether the loop goes through a copy of what it iterates, taken before the
         * first iteration. Only a variable (the collection, or the text that split and words cut up) needs
         * one, and only when the body may change it; anything else is a value of its own already, and a
         * variable the body leaves alone holds the same value all along.
         */
        public boolean snapshot;
        /**
         * `for x in mut xs`: x is each element itself, not a copy, and what the body does to x it does to the element;
         * for a map, `for k, v in mut m` gives each value so. The body cannot use xs otherwise, so no element moves.
         */
        public boolean inPlace;

        public For(Span span, String name, Span nameSpan, String indexName, Span indexSpan, Expr from, Expr to,
                   boolean inclusive, Expr step, boolean rev, Block body) {
            super(span);
            this.name = name;
            this.nameSpan = nameSpan;
            this.indexName = indexName;
            this.indexSpan = indexSpan;
            this.from = from;
            this.to = to;
            this.inclusive = inclusive;
            this.step = step;
            this.rev = rev;
            this.body = body;
        }

        /** Whether the range counts other than up by one from its start: a step, `..=` or `rev`. */
        public boolean stepped() {
            return step != null || inclusive || rev;
        }

        public boolean isRange() {
            return to != null;
        }

        /**
         * The split or words call whose pieces the loop goes through, or null for a range or a collection. A
         * call with the wrong number of arguments is neither: the checker reports it, and the loop never runs.
         */
        public Call pieces() {
            if (to != null || !(from instanceof Call c)) return null;
            int arity = c.builtin == Builtin.SPLIT ? 2 : c.builtin == Builtin.WORDS || c.builtin == Builtin.CHARS ? 1 : -1;
            return c.args.size() == arity ? c : null;
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
        /**
         * `if let P = e { A } else { B }`, which is this match: `match e { P => { A } _ => { B } }`; the `_` arm is the
         * last, and never unreachable unless P always matches.
         */
        public final boolean ifLet;

        public Match(Span span, Expr subject, List<Arm> arms) {
            this(span, subject, arms, false);
        }

        public Match(Span span, Expr subject, List<Arm> arms, boolean ifLet) {
            super(span);
            this.subject = subject;
            this.arms = arms;
            this.ifLet = ifLet;
        }
    }

    /** A match arm; {@code guard} is the condition of `Pattern if cond =>`, or null. */
    public record Arm(Pattern pattern, Expr guard, Block body) {}

    /**
     * `break;` or `break label;`: {@code target} is the loop it leaves, set by the checker for a label. `break value;`
     * leaves the innermost loop around it that gives a value ({@link LoopExpr}), with that value: the checker sets its
     * target.
     */
    public static final class Break extends Stmt {
        /** The label, or null; the checker reads `break x;` with no loop labelled x as the value x, moving it to value. */
        public String label;
        public Span labelSpan;
        public Expr value;
        public Loop target;

        public Break(Span span, String label) {
            this(span, label, null);
        }

        public Break(Span span, String label, Expr value) {
            super(span);
            this.label = label;
            this.value = value;
        }
    }

    /** `continue;` or `continue label;`: {@code target} is the loop it continues, set by the checker for a label. */
    public static final class Continue extends Stmt {
        public final String label;
        public Loop target;

        public Continue(Span span, String label) {
            super(span);
            this.label = label;
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

    /** `Enum.Variant` or `Enum.Variant(p1, p2)`: a variant whose payload values match the patterns. */
    public static final class VariantPat extends Pattern {
        /** The alias of `ns.Enum.Variant`, or null. */
        public String qualifier;
        public final String enumName;
        public final String variantName;
        /** The patterns of the payload values, or null if the pattern has no parentheses. */
        public final List<Pattern> args;
        public Variant variant;

        public VariantPat(Span span, String enumName, String variantName, List<Pattern> args) {
            super(span);
            this.enumName = enumName;
            this.variantName = variantName;
            this.args = args;
        }
    }

    /** A name: matches anything, and binds the value to a new variable. */
    public static final class BindPat extends Pattern {
        public final String name;
        public LocalVar var;

        public BindPat(Span span, String name) {
            super(span);
            this.name = name;
        }
    }

    /** `(p1, p2, ...)`: a tuple whose elements match the patterns. */
    public static final class TuplePat extends Pattern {
        public final List<Pattern> elems;

        public TuplePat(Span span, List<Pattern> elems) {
            super(span);
            this.elems = elems;
        }
    }

    /**
     * `[p1, p2]`: an array, list or str of exactly those elements; with `..`, `[p1, .., pk]`, of at least them: the
     * prefix from the front, the suffix at the back, and any number of elements between.
     */
    public static final class ListPat extends Pattern {
        public final List<Pattern> prefix;
        public final boolean rest;
        public final List<Pattern> suffix;

        public ListPat(Span span, List<Pattern> prefix, boolean rest, List<Pattern> suffix) {
            super(span);
            this.prefix = prefix;
            this.rest = rest;
            this.suffix = suffix;
        }

        /** How many elements it names: all it matches without `..`, the fewest with it. */
        public int named() {
            return prefix.size() + suffix.size();
        }
    }

    /** `Point(x: p1, y: p2)`: a struct whose named fields match the patterns; fields it leaves out match anything. */
    public static final class StructPat extends Pattern {
        /** The alias of `ns.Point(...)`, or null. */
        public String qualifier;
        public final String name;
        public final List<String> fieldNames;
        public final List<Span> fieldSpans;
        public final List<Pattern> fields;
        public StructDecl decl;
        /** For each entry of {@link #fields}, the index of its field in the struct. */
        public int[] fieldIndexes;

        public StructPat(Span span, String name, List<String> fieldNames, List<Span> fieldSpans, List<Pattern> fields) {
            super(span);
            this.name = name;
            this.fieldNames = fieldNames;
            this.fieldSpans = fieldSpans;
            this.fields = fields;
        }
    }

    /** `lo..=hi` or `lo..hi`: an integer from lo to hi, which {@link #hi} holds inclusive either way. */
    public static final class RangePat extends Pattern {
        public final BigInteger lo;
        public final BigInteger hi;
        public final boolean inclusive;
        /** The integer type it matches, set by the checker. */
        public PrimKind kind;
        /** A bound is a character beyond ASCII, a code point (see {@link IntLit#codePoint}). */
        public boolean codePoint;

        public RangePat(Span span, BigInteger lo, BigInteger hi, boolean inclusive) {
            super(span);
            this.lo = lo;
            this.hi = inclusive ? hi : hi.subtract(BigInteger.ONE);
            this.inclusive = inclusive;
        }
    }

    /** `p1 | p2 | ...`: any of the alternatives; each binds the same names, to the same variables. */
    public static final class OrPat extends Pattern {
        public final List<Pattern> alts;

        public OrPat(Span span, List<Pattern> alts) {
            super(span);
            this.alts = alts;
        }
    }

    public static final class IntPat extends Pattern {
        public final BigInteger value;
        /** A character beyond ASCII, a code point (see {@link IntLit#codePoint}). */
        public boolean codePoint;

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

    /** The names a pattern binds, in order; each of an or-pattern's alternatives binds the same ones, once listed. */
    public static List<BindPat> bindings(Pattern p) {
        List<BindPat> out = new ArrayList<>();
        bindings(p, out, false);
        return out;
    }

    /** Every name of a pattern, those of each alternative of an or-pattern included: where each is written. */
    public static List<BindPat> allBindings(Pattern p) {
        List<BindPat> out = new ArrayList<>();
        bindings(p, out, true);
        return out;
    }

    private static void bindings(Pattern p, List<BindPat> out, boolean everyAlternative) {
        switch (p) {
            case BindPat b -> out.add(b);
            case VariantPat v -> {
                if (v.args != null) for (Pattern a : v.args) bindings(a, out, everyAlternative);
            }
            case TuplePat t -> {
                for (Pattern e : t.elems) bindings(e, out, everyAlternative);
            }
            case ListPat l -> {
                for (Pattern e : l.prefix) bindings(e, out, everyAlternative);
                for (Pattern e : l.suffix) bindings(e, out, everyAlternative);
            }
            case StructPat s -> {
                for (Pattern f : s.fields) bindings(f, out, everyAlternative);
            }
            case OrPat o -> {
                if (everyAlternative) for (Pattern a : o.alts) bindings(a, out, true);
                else bindings(o.alts.get(0), out, false);
            }
            default -> { }
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
            return this instanceof Name n && n.function == null && n.capacity == null || this instanceof Index
                || (this instanceof FieldAccess f && f.variant == null && f.constant == null && f.function == null);
        }
    }

    /** Integer or character literal. */
    public static final class IntLit extends Expr {
        public final BigInteger value;
        public final boolean isChar;
        /** A character literal beyond ASCII, 'é' or '\\u{20AC}': its value is a code point, never a byte (a u8). */
        public boolean codePoint;

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
        /** A capacity parameter, N of `fn f<N>(...)`, in an instance of the function: the capacity it stands for. */
        public Long capacity;
        /** A function used as a value: `let f: fn(i64) -> i64 = inc;`. */
        public FnDecl function;

        public Name(Span span, String name) {
            super(span);
            this.name = name;
        }
    }

    /** `target.name`: a struct field, a tuple element (name "0", "1", ...), or a unit enum variant when target names an enum. */
    public static final class FieldAccess extends Expr {
        public final Expr target;
        public final String name;
        public final Span nameSpan;
        public int fieldIndex = -1;
        public Variant variant;
        /** `ns.NAME`: a constant of the file imported as ns. */
        public ConstDecl constant;
        /** `ns.f` as a value: a function of the file imported as ns. */
        public FnDecl function;

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

    /**
     * `target[from..to]`, with either end left out for 0 or the length: the elements or bytes from..to, as a list
     * (for an array) or a str or list of the target's capacity. Where it is only read, the C backend reads it where it
     * is ({@code view}) instead of copying it.
     */
    public static final class SliceExpr extends Expr {
        public final Expr target;
        public final Expr from;
        public final Expr to;
        public boolean view;

        public SliceExpr(Span span, Expr target, Expr from, Expr to) {
            super(span);
            this.target = target;
            this.from = from;
            this.to = to;
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
        /**
         * A call of a variable that holds a function value (a parameter of a function type, in a function checked as
         * written or in an instance given a function value rather than a name): which one.
         */
        public LocalVar fnParam;
        /**
         * For a builtin such as any or sort_by given a variable that holds a function value rather than a function's
         * name: that variable, whose function it calls.
         */
        public LocalVar fnArgLocal;
        /**
         * The function that a builtin such as any or sort_by calls: the one passed to it by name. Null in a function
         * checked as written that passes on a parameter of a function type; its instances know the function.
         */
        public FnDecl fnArg;
        /**
         * A fixed, pad_left or pad_right call whose text print or append writes out directly, or pad_left or
         * pad_right pads: it builds no str, and its type is a nominal str[0].
         */
        public boolean asText;
        /**
         * How many arguments the call writes, once the checker has added the default values of the parameters it leaves
         * out to {@link #args}; -1 before.
         */
        public int given = -1;
        /** `P()` of a struct whose fields all have default values: the struct value it builds. */
        public StructLit asStruct;
        /** variants(E): the enum E, once the checker has replaced the argument with its variants, E.A, E.B, ... */
        public EnumDecl variantsOf;

        public Call(Span span, String name, Span nameSpan, List<Arg> args) {
            super(span);
            this.name = name;
            this.nameSpan = nameSpan;
            this.args = args;
        }

        public boolean hasMutArg() {
            return args.stream().anyMatch(Arg::mut);
        }

        /** The result type of the function a builtin such as transform or sort_by_key calls: by name or held in a variable. */
        public Type fnArgResult() {
            return fnArg != null ? fnArg.ret : ((Type.FnT) fnArgLocal.type).ret();
        }
    }

    /** `Enum.Variant(args)`. */
    public static final class VariantCall extends Expr {
        /** The alias of `ns.Enum.Variant(...)`, or null. */
        public String qualifier;
        /** `ns.f(...)`, which reads as Enum.Variant(...): the call of the function f of the file imported as ns. */
        public Call asCall;
        /** `s.f(...)`, which reads as Enum.Variant(...): the call of the function value in the field f of the variable s. */
        public CallValue asValueCall;
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

    /** `callee(args)`, where callee is any expression that gives a function value: `ops[i](x)`, `s.handler(x)`. */
    public static final class CallValue extends Expr {
        public final Expr callee;
        public final List<Arg> args;

        public CallValue(Span span, Expr callee, List<Arg> args) {
            super(span);
            this.callee = callee;
            this.args = args;
        }
    }

    /** `Point(x: 1, y: 2)`. */
    public static final class StructLit extends Expr {
        /** The alias of `ns.Point(...)`, or null. */
        public String qualifier;
        public final String name;
        public final List<FieldInit> fields;
        public StructDecl decl;
        /** `P(..base, x: 1)`: the value whose fields the literal does not name come from, or null. */
        public Expr base;
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

    /**
     * `e?`: the value in e, an Option or a Result, when it is Option.Some or Result.Ok; when it is Option.None or
     * Result.Err, the function returns that at once.
     */
    public static final class Try extends Expr {
        public final Expr inner;
        /** Set by the checker: the return type of the function, an Option or a Result, which a failure is returned as. */
        public Type ret;

        public Try(Span span, Expr inner) {
            super(span);
            this.inner = inner;
        }
    }

    /** `(a, b, ...)`: a tuple. */
    public static final class TupleLit extends Expr {
        public final List<Expr> elems;

        public TupleLit(Span span, List<Expr> elems) {
            super(span);
            this.elems = elems;
        }
    }

    /** An arm of a match expression: `Pattern if guard => value`. */
    public record ExprArm(Pattern pattern, Expr guard, Expr value) {}

    /** `match e { P => a, Q => b }`: the value of the first arm whose pattern (and guard) matches; only it is evaluated. */
    public static final class MatchExpr extends Expr {
        public final Expr subject;
        public final List<ExprArm> arms;

        public MatchExpr(Span span, Expr subject, List<ExprArm> arms) {
            super(span);
            this.subject = subject;
            this.arms = arms;
        }
    }

    /**
     * `for ... { ... break v; ... } else e` or the same with `while`: a loop that gives a value, the whole value of a
     * let, var, assignment or return. It is the value of the `break v;` that leaves it, or e when it ends without one.
     */
    public static final class LoopExpr extends Expr {
        public final Loop loop;
        public final Expr otherwise;

        public LoopExpr(Span span, Loop loop, Expr otherwise) {
            super(span);
            this.loop = loop;
            this.otherwise = otherwise;
        }
    }

    /** `if cond then a else b`: a or b, whichever cond picks; only that one is evaluated. */
    public static final class IfExpr extends Expr {
        public final Expr cond;
        public final Expr then;
        public final Expr otherwise;

        public IfExpr(Span span, Expr cond, Expr then, Expr otherwise) {
            super(span);
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
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
