package alang.check;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.diag.Code;
import alang.diag.Diagnostic;
import alang.diag.Diagnostics;
import alang.diag.Hints;
import alang.diag.Span;
import alang.interp.Interpreter;
import alang.interp.Trap;
import alang.types.PrimKind;
import alang.types.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic analysis: name resolution, type checking, mutability, aliasing, exhaustiveness,
 * definite return, the recursion ban and the static memory bound.
 */
public final class Checker {
    public static final int MAX_SIZE = 1 << 20;

    /**
     * The memory budget the command line applies unless --max-stack says otherwise: 8 MiB, the default
     * thread stack on Linux and macOS. Every entry point's worst-case stack must fit in it (E0402), and
     * so must the constants, which are evaluated while checking (E0405). 0 turns the limit off.
     */
    public static final long DEFAULT_MAX_STACK = 8L << 20;

    private final Program program;
    /** Where diagnostics go; while an instance is checked, somewhere else (see {@link #instance}). */
    private Diagnostics diags;

    private final Map<String, StructDecl> structs = new LinkedHashMap<>();
    private final Map<String, EnumDecl> enums = new LinkedHashMap<>();
    private final Map<String, ConstDecl> consts = new LinkedHashMap<>();
    private final Map<String, FnDecl> fns = new LinkedHashMap<>();

    /** Per-routine checking context; swapped out while checking constants lazily. */
    private static final class Ctx {
        Routine routine;
        FnDecl fn;
        final Deque<Map<String, LocalVar>> scopes = new ArrayDeque<>();
        int loopDepth;
        /** The loops around the statement being checked, innermost first. */
        final Deque<Loop> loops = new ArrayDeque<>();
        boolean inEnsures;
        Expr mutCallAllowed;
        /**
         * The expression stored by the let/var, assignment, return or constant being checked, whose type it
         * then takes: split, words, join and replace build a value of that type.
         */
        Expr stored;
        /** The split or words call of the `for` loop being checked, which goes through its pieces one at a time. */
        Call pieces;
        /** The argument of print or append, or the value of pad_left or pad_right, being checked: it is written out as text. */
        Expr text;
    }

    private Ctx ctx = new Ctx();

    /** The loop that declares each `for` variable, and every match binding: E0205 hints differ for them. */
    private final Map<LocalVar, For> loopVars = new HashMap<>();
    private final Set<LocalVar> matchBindings = new HashSet<>();
    /** What each let/var was initialized with: E0205 says when a variable is a copy of another's element or field. */
    private final Map<LocalVar, Expr> letInits = new HashMap<>();

    /** The memory budget in bytes (0: none), and the bytes of the constants evaluated so far. */
    private final long maxStack;
    private long staticBytes;

    private Checker(Program program, Diagnostics diags, long maxStack) {
        this.program = program;
        this.diags = diags;
        this.maxStack = maxStack;
    }

    /** Checks the program; {@code maxStack} is the memory budget in bytes, 0 for none. */
    public static CheckResult check(Program program, Diagnostics diags, long maxStack) {
        Checker c = new Checker(program, diags, maxStack);
        return c.run();
    }

    private CheckResult run() {
        collect();
        for (StructDecl s : structs.values()) ensureStruct(s);
        for (EnumDecl e : enums.values()) ensureEnum(e);
        boolean recursiveTypes = checkRecursiveTypes();
        for (ConstDecl c : consts.values()) ensureConst(c);
        for (FnDecl f : fns.values()) signature(f);
        Set<FnDecl> failed = new HashSet<>();
        for (Item item : program.items) {
            int before = diags.errorCount();
            if (item instanceof FnDecl f && fns.get(f.name) == f) body(f);
            if (item instanceof TestDecl t) testBody(t);
            if (item instanceof FnDecl f && diags.errorCount() != before) failed.add(f);
        }
        checkInstances(failed);
        boolean recursion = checkRecursion();
        MemoryReport memory = null;
        if (!diags.hasErrors() && !recursiveTypes && !recursion) {
            // Only now: in a program with errors, a name can look unused because the code using it failed to check.
            Uses.warnUnused(program, diags, this::exprText);
            memory = MemoryReport.compute(program);
            if (maxStack > 0) {
                for (MemoryReport.Entry e : memory.roots) {
                    if (e.stackBytes() > maxStack) {
                        diags.error(Code.E0402, e.routine().span,
                            e.label() + " needs up to " + e.stackBytes() + " bytes of stack, more than the budget of "
                                + maxStack + " bytes",
                            "reduce array/list/str capacities, or pass large values as `mut` parameters; "
                                + "`alang check --memory` shows where the bytes go, and --max-stack sets the budget");
                    }
                }
            }
        }
        return new CheckResult(program, memory);
    }

    // =================================================================== declarations

    private void collect() {
        Set<String> testNames = new HashSet<>();
        for (Item item : program.items) {
            switch (item) {
                case StructDecl s -> {
                    if (typeExists(s.name)) duplicate(s.span, "type", s.name, typeSpan(s.name));
                    else structs.put(s.name, s);
                    Set<String> seen = new HashSet<>();
                    for (Field f : s.fields) {
                        if (!seen.add(f.name)) duplicate(f.span, "field", f.name);
                    }
                }
                case EnumDecl e -> {
                    if (typeExists(e.name)) duplicate(e.span, "type", e.name, typeSpan(e.name));
                    else enums.put(e.name, e);
                    Set<String> seen = new HashSet<>();
                    for (Variant v : e.variants) {
                        if (!seen.add(v.name)) duplicate(v.span, "variant", v.name);
                    }
                }
                case ConstDecl c -> {
                    if (consts.containsKey(c.name)) duplicate(c.span, "constant", c.name, consts.get(c.name).span);
                    else consts.put(c.name, c);
                }
                case FnDecl f -> {
                    Builtin b = Builtin.byName(f.name);
                    if (b != null) {
                        diags.error(Code.E0108, f.nameSpan, "`" + f.name + "` is a builtin function and cannot be redefined",
                            b.group == Builtin.Group.CORE ? "choose a different name, e.g. my_" + f.name
                                : "the standard library has " + b.signature + "; call it, or choose a different name, e.g. my_" + f.name);
                    } else if (fns.containsKey(f.name)) {
                        duplicate(f.nameSpan, "function", f.name, fns.get(f.name).nameSpan);
                    } else {
                        fns.put(f.name, f);
                    }
                }
                case TestDecl t -> {
                    if (!testNames.add(t.name)) duplicate(t.span, "test", "\"" + t.name + "\"");
                }
            }
        }
        for (ConstDecl c : consts.values()) {
            if (typeExists(c.name)) {
                diags.error(Code.E0104, c.span, "constant `" + c.name + "` has the same name as a type");
            }
        }
        // Items share one namespace (SPEC §3), even where the syntax would tell them apart: Color() and Color.Red.
        for (FnDecl f : fns.values()) {
            if (structs.containsKey(f.name)) {
                diags.error(Code.E0104, f.nameSpan, "function `" + f.name + "` has the same name as a struct",
                    "struct literals and calls share syntax; rename one of them");
            } else if (enums.containsKey(f.name)) {
                diags.error(Code.E0104, f.nameSpan, "function `" + f.name + "` has the same name as an enum",
                    "names are unique across functions, types and constants; rename one of them");
            }
            if (consts.containsKey(f.name)) {
                diags.error(Code.E0104, f.nameSpan, "function `" + f.name + "` has the same name as a constant",
                    "names are unique across functions, types and constants; rename one of them");
            }
        }
    }

    private boolean typeExists(String name) {
        return structs.containsKey(name) || enums.containsKey(name);
    }

    private void duplicate(Span span, String what, String name) {
        duplicate(span, what, name, null);
    }

    /** A duplicate of the declaration at {@code first}; the message names its file when that is another one. */
    private void duplicate(Span span, String what, String name, Span first) {
        String also = first == null || first.file() == span.file() || first.file() == null ? ""
            : ": " + first.file().path + " declares it too, at line " + first.line();
        diags.error(Code.E0104, span, "duplicate " + what + " `" + name + "`" + also, "rename or remove one of the declarations");
    }

    private Span typeSpan(String name) {
        return structs.containsKey(name) ? structs.get(name).span : enums.get(name).span;
    }

    private void ensureStruct(StructDecl s) {
        if (s.state != State.NEW) return;
        s.state = State.IN_PROGRESS;
        Ctx saved = enterTopLevel();
        for (Field f : s.fields) f.type = resolveType(f.typeRef);
        ctx = saved;
        s.state = State.DONE;
    }

    private void ensureEnum(EnumDecl e) {
        if (e.state != State.NEW) return;
        e.state = State.IN_PROGRESS;
        Ctx saved = enterTopLevel();
        for (Variant v : e.variants) {
            v.payload.clear();
            for (TypeRef r : v.payloadRefs) v.payload.add(resolveType(r));
        }
        ctx = saved;
        e.state = State.DONE;
    }

    private Ctx enterTopLevel() {
        Ctx saved = ctx;
        ctx = new Ctx();
        return saved;
    }

    private void ensureConst(ConstDecl c) {
        if (c.state == State.DONE) return;
        if (c.state == State.IN_PROGRESS) {
            diags.error(Code.E0209, c.span, "constant `" + c.name + "` depends on itself");
            return;
        }
        c.state = State.IN_PROGRESS;
        Ctx saved = enterTopLevel();
        // Count errors from the declared type too: a constant whose type is unknown must not get a
        // value, or constants computed from it would be evaluated with an error type.
        int before = diags.errorCount();
        c.type = resolveType(c.typeRef);
        ctx.stored = c.init;
        Type t = expr(c.init, c.type);
        ctx.stored = null;
        requireAssignable(c.init, t, c.type);
        // An error-typed initializer (e.g. one using a constant that already failed) was reported
        // where it went wrong; skip evaluation without piling on another diagnostic.
        if (diags.errorCount() == before && !Type.isError(t)) {
            if (!isConst(c.init)) {
                diags.error(Code.E0209, c.init.span, "the value of constant `" + c.name + "` is not a constant expression",
                    "constants may use literals, other constants, operators, conversions and the builtins that only compute "
                        + "(such as len, pow or split), but not other function calls");
            } else if (withinBudget(c)) {
                c.value = evalConst(c.init);
            }
        }
        // A constant without a value is error-typed, so its uses (other constants, array sizes)
        // stay quiet instead of each reporting a follow-on error.
        if (c.value == null) c.type = Type.ERROR;
        ctx = saved;
        c.state = State.DONE;
    }

    /**
     * Constants are evaluated now, in this process, so their size is checked first: a constant such as
     * [[0; 1048576]; 1048576] would otherwise exhaust the memory of the checker itself.
     */
    private boolean withinBudget(ConstDecl c) {
        long size = Layout.size(c.type);
        long need = Layout.add(staticBytes, Layout.add(size, Temporaries.expr(c.init)));
        if (maxStack > 0 && need > maxStack) {
            diags.error(Code.E0405, c.span, "constant `" + c.name + "` needs up to " + need
                    + " bytes, counting the constants before it and the values its initializer builds; the budget is "
                    + maxStack + " bytes",
                "make the constant smaller, or build the data at run time in a function; --max-stack sets the budget");
            return false;
        }
        staticBytes = Layout.add(staticBytes, size);
        return true;
    }

    private Object evalConst(Expr e) {
        try {
            return Interpreter.evalConst(e);
        } catch (Trap t) {
            diags.error(Code.E0209, e.span, "constant evaluation failed: " + t.getMessage());
            return null;
        }
    }

    // =================================================================== types

    private Type resolveType(TypeRef ref) {
        return switch (ref) {
            case PrimRef p -> Type.prim(p.kind());
            case StrRef s -> {
                int cap = s.cap() == null ? anyCapacity(s.span(), "str[_]") : typeSize(s.cap(), "string capacity");
                yield cap == BAD_SIZE ? Type.ERROR : new Type.Str(cap);
            }
            case ListRef l -> {
                Type elem = resolveType(l.elem());
                int cap = l.cap() == null ? anyCapacity(l.span(), "list[" + elem + "; _]") : typeSize(l.cap(), "list capacity");
                yield cap == BAD_SIZE || Type.isError(elem) ? Type.ERROR : new Type.ListT(elem, cap);
            }
            case ArrayRef a -> {
                Type elem = resolveType(a.elem());
                int len = a.len() == null ? anyCapacity(a.span(), "[" + elem + "; _]") : typeSize(a.len(), "array length");
                yield len == BAD_SIZE || Type.isError(elem) ? Type.ERROR : new Type.ArrayT(elem, len);
            }
            case NamedRef n -> {
                if (structs.containsKey(n.name())) yield new Type.StructT(structs.get(n.name()));
                if (enums.containsKey(n.name())) yield new Type.EnumT(enums.get(n.name()));
                diags.error(Code.E0103, n.span(), "unknown type `" + n.name() + "`", typeHint(n.name()));
                yield Type.ERROR;
            }
        };
    }

    /** What {@link #typeSize} and {@link #anyCapacity} return after reporting an error. */
    private static final int BAD_SIZE = Integer.MIN_VALUE;

    /** A constant capacity or length of a type, or BAD_SIZE after reporting an error. */
    private int typeSize(Expr e, String what) {
        int n = constSize(e, what);
        return n < 0 ? BAD_SIZE : n;
    }

    /**
     * The `_` of a type: in a parameter's type, the next unknown capacity of the function (see
     * {@link Type#anyCapacity}); anywhere else, an error.
     */
    private int anyCapacity(Span span, String written) {
        if (paramCapacities != null) return Type.anyCapacity(paramCapacities[0]++);
        boolean str = written.startsWith("str");
        diags.error(Code.E0217, span, "`_` stands for any capacity only in the type of a function parameter",
            "write a constant here, e.g. " + written.replace("_", str ? "64" : "16") + "; "
                + (str ? "a parameter s: str[_] fits it with str[64](s), which traps (R0004) if it is longer"
                    : "a parameter xs: list[T; _] fits a list[T; 16] with list[T; 16](xs), which traps (R0004) if it is longer"));
        return BAD_SIZE;
    }

    /** While a parameter's type is resolved: how many `_`s the function's parameters have so far. */
    private int[] paramCapacities;

    private String typeHint(String name) {
        return switch (name) {
            case "int", "long", "isize", "usize", "Int", "integer" -> "use a sized integer type such as i32 or i64 (len() returns i64)";
            case "uint" -> "use u32 or u64";
            case "float", "double", "number", "Float" -> "use f64 (or f32)";
            case "string", "String", "text", "char_array" -> "strings have a fixed capacity: str[64]";
            case "char", "byte" -> "use u8 for single bytes; character literals like 'a' are u8-compatible";
            case "boolean", "Bool" -> "use bool";
            case "void", "unit", "None" -> "omit the return type for functions that return nothing";
            case "Vec", "List", "Array", "vector" -> "use list[T; N] (up to N items) or [T; N] (exactly N items)";
            case "Option", "Optional", "Result" -> "there are no generics; declare an enum, e.g. enum MaybeI32 { None, Some(i32) }";
            default -> {
                List<String> names = new ArrayList<>(structs.keySet());
                names.addAll(enums.keySet());
                for (PrimKind k : PrimKind.values()) names.add(k.name);
                yield didYouMean(name, names);
            }
        };
    }

    /** Evaluates a constant size expression; returns -1 after reporting an error. */
    private int constSize(Expr e, String what) {
        // The constants it uses are resolved now, and a `_` in their types is not a parameter's.
        int[] savedCapacities = paramCapacities;
        paramCapacities = null;
        try {
            return constSizeOf(e, what);
        } finally {
            paramCapacities = savedCapacities;
        }
    }

    private int constSizeOf(Expr e, String what) {
        int before = diags.errorCount();
        Type t = expr(e, Type.I64);
        if (diags.errorCount() != before || Type.isError(t)) return -1;
        if (!Type.isInt(t)) {
            diags.error(Code.E0210, e.span, what + " must be an integer constant, found " + t);
            return -1;
        }
        if (!isConst(e)) {
            diags.error(Code.E0209, e.span, what + " must be a compile-time constant",
                "use an integer literal or a `const`, e.g. const N: i64 = 16;");
            return -1;
        }
        // Evaluated now, in this process, like a constant: what the expression builds on the way must fit the
        // same budget, or str[len([[0; 1048576]; 1048576])] would exhaust the memory of the checker itself.
        long need = Layout.add(staticBytes, Temporaries.expr(e));
        if (maxStack > 0 && need > maxStack) {
            diags.error(Code.E0405, e.span, what + " needs up to " + need + " bytes to compute, counting the constants; "
                    + "the budget is " + maxStack + " bytes",
                "compute it from smaller values, or use an integer literal or a `const`; --max-stack sets the budget");
            return -1;
        }
        Object v = evalConst(e);
        if (v == null) return -1;
        long n = (Long) v;
        // A u64 above 2^63 is negative as a long, so `n < 1` also rejects it.
        if (n < 1 || n > MAX_SIZE) {
            String found = Type.kind(t) == PrimKind.U64 ? Long.toUnsignedString(n) : Long.toString(n);
            diags.error(Code.E0210, e.span, what + " must be between 1 and " + MAX_SIZE + ", found " + found);
            return -1;
        }
        return (int) n;
    }

    private boolean checkRecursiveTypes() {
        boolean found = false;
        List<Item> types = new ArrayList<>(structs.values());
        types.addAll(enums.values());
        for (Item d : types) {
            Type t = d instanceof StructDecl s ? new Type.StructT(s) : new Type.EnumT((EnumDecl) d);
            if (contains(t, d, new HashSet<>())) {
                diags.error(Code.E0403, d.span, (t instanceof Type.StructT ? "struct" : "enum") + " `" + d.name
                    + "` contains itself and would have infinite size",
                    "store related items in a fixed-capacity list and refer to them by index");
                found = true;
            }
        }
        return found;
    }

    /** Does a value of type {@code t} (transitively) contain the declaration {@code target}? */
    private boolean contains(Type t, Object target, Set<Object> visiting) {
        List<Type> children = new ArrayList<>();
        Object key = null;
        switch (t) {
            case Type.StructT s -> {
                key = s.decl();
                for (Field f : s.decl().fields) children.add(f.type);
            }
            case Type.EnumT e -> {
                key = e.decl();
                for (Variant v : e.decl().variants) children.addAll(v.payload);
            }
            case Type.ArrayT a -> children.add(a.elem());
            case Type.ListT l -> children.add(l.elem());
            default -> {
            }
        }
        if (key != null && !visiting.add(key)) return false;
        for (Type c : children) {
            if (c instanceof Type.StructT s && s.decl() == target) return true;
            if (c instanceof Type.EnumT e && e.decl() == target) return true;
            if (contains(c, target, visiting)) return true;
        }
        return false;
    }

    // =================================================================== functions

    private final Set<FnDecl> signed = new HashSet<>();

    /** Resolves parameter and return types; may run lazily (e.g. from a constant initializer). */
    private void signature(FnDecl f) {
        if (!signed.add(f)) return;
        Ctx saved = ctx;
        ctx = new Ctx();
        ctx.routine = f;
        ctx.fn = f;
        f.ret = f.retRef == null ? Type.VOID : resolveType(f.retRef);
        Set<String> names = new HashSet<>();
        int[] savedCapacities = paramCapacities;
        paramCapacities = new int[1];
        for (Param p : f.params) {
            Type t = resolveType(p.typeRef());
            // An instance's parameters have the capacities of the call that made it in place of their `_`s.
            if (f.origin != null) t = Type.substitute(t, f.capacities);
            if (Type.hasAnyCapacity(t) && (f.tool || f.name.equals("main"))) {
                diags.error(Code.E0217, p.typeRef().span(), (f.tool ? "a tool's parameters need" : "the input of `main` needs")
                        + " a constant capacity, not `_`",
                    f.tool ? "the tool's JSON schema states it (maxLength, maxItems); write the most it accepts, e.g. "
                        + p.name() + ": " + t.toString().replace("_", t instanceof Type.Str ? "256" : "16")
                        : "write the most bytes of input the program accepts: " + p.name() + ": str[4096]");
                t = Type.ERROR;
            }
            if (!names.add(p.name())) {
                diags.error(Code.E0105, p.span(), "duplicate parameter `" + p.name() + "`");
            } else {
                checkNameFree(p.name(), p.span());
            }
            if (p.name().equals("result") && !f.ensures.isEmpty()) {
                diags.error(Code.E0105, p.span(), "parameter `result` conflicts with `result` in ensures",
                    "rename the parameter");
            }
            LocalVar v = new LocalVar(p.name(), p.span(), t, p.mut(), p.mut(), f.locals.size());
            f.locals.add(v);
            f.paramVars.add(v);
            if (f.tool && p.mut()) {
                diags.error(Code.E0601, p.span(), "tool functions cannot take `mut` parameters",
                    "tools receive JSON arguments; return the new value instead");
            }
        }
        paramCapacities = savedCapacities;
        if (f.name.equals("main") && (f.params.size() > 1 || f.retRef != null)) {
            diags.error(Code.E0602, f.nameSpan, "`main` must return nothing and take at most one parameter",
                "declare it as fn main() { ... }, or as fn main(input: str[4096]) { ... } to receive the standard input");
        } else if (f.name.equals("main") && f.params.size() == 1) {
            Param p = f.params.get(0);
            if (p.mut() || !(f.paramVars.get(0).type instanceof Type.Str) && f.paramVars.get(0).type != Type.ERROR) {
                diags.error(Code.E0602, p.span(), "the parameter of `main` must be a read-only str: it receives the standard input",
                    "declare it as " + p.name() + ": str[4096] (the most bytes of input the program accepts), and split "
                        + "it as needed: let lines: list[str[80]; 64] = split(" + p.name() + ", '\\n');");
            }
        }
        if (!f.ensures.isEmpty() && !(f.ret instanceof Type.Void)) {
            f.resultVar = new LocalVar("result", f.nameSpan, f.ret, false, false, f.locals.size());
            f.locals.add(f.resultVar);
        }
        ctx = saved;
    }

    private void body(FnDecl f) {
        ctx = new Ctx();
        ctx.routine = f;
        ctx.fn = f;
        Map<String, LocalVar> params = new HashMap<>();
        for (LocalVar v : f.paramVars) params.putIfAbsent(v.name, v);
        ctx.scopes.push(params);
        for (Expr r : f.requires) condition(r, "requires");
        ctx.inEnsures = true;
        for (Expr e : f.ensures) condition(e, "ensures");
        ctx.inEnsures = false;
        boolean jumps = block(f.body);
        if (!jumps && !(f.ret instanceof Type.Void) && !Type.isError(f.ret)) {
            diags.error(Code.E0301, f.nameSpan, "function `" + f.name + "` does not return a value on every path",
                "add `return <" + f.ret + ">;` at the end of the function (loops never count as returning)");
        }
        ctx.scopes.pop();
    }

    private void testBody(TestDecl t) {
        ctx = new Ctx();
        ctx.routine = t;
        ctx.scopes.push(new HashMap<>());
        block(t.body);
        ctx.scopes.pop();
    }

    private boolean checkRecursion() {
        Map<FnDecl, Integer> state = new HashMap<>();
        boolean found = false;
        for (FnDecl f : fns.values()) {
            List<FnDecl> path = new ArrayList<>();
            List<FnDecl> cycle = findCycle(f, state, path);
            if (cycle != null) {
                found = true;
                diags.error(Code.E0401, cycle.get(0).nameSpan,
                    "recursion is not allowed: " + String.join(" -> ", cycle.stream().map(c -> c.name).toList()),
                    "rewrite with a loop; keep pending work in a fixed-capacity list (a stack) if needed");
            }
        }
        return found;
    }

    /** DFS that returns a cycle (first element repeated at the end) or null. */
    private List<FnDecl> findCycle(FnDecl f, Map<FnDecl, Integer> state, List<FnDecl> path) {
        Integer st = state.get(f);
        if (st != null && st == 2) return null;
        if (st != null && st == 1) {
            int i = path.indexOf(f);
            List<FnDecl> cycle = new ArrayList<>(path.subList(i, path.size()));
            cycle.add(f);
            return cycle;
        }
        state.put(f, 1);
        path.add(f);
        for (FnDecl callee : f.callees) {
            // Recursion is a property of the functions as written: an instance stands for the function it copies.
            FnDecl c = callee.origin != null ? callee.origin : callee;
            List<FnDecl> cycle = findCycle(c, state, path);
            if (cycle != null) {
                // Mark the rest as done so the same cycle is reported once.
                for (FnDecl p : path) state.put(p, 2);
                return cycle;
            }
        }
        path.remove(path.size() - 1);
        state.put(f, 2);
        return null;
    }

    // =================================================================== scopes

    private void checkNameFree(String name, Span span) {
        if (consts.containsKey(name)) {
            diags.error(Code.E0105, span, "`" + name + "` is already a constant", "choose a different name");
        } else if (typeExists(name)) {
            diags.error(Code.E0105, span, "`" + name + "` is already a type", "choose a different name");
        }
    }

    private LocalVar declare(String name, Span span, Type type, boolean mutable) {
        for (Map<String, LocalVar> scope : ctx.scopes) {
            LocalVar prev = scope.get(name);
            if (prev != null) {
                diags.error(Code.E0105, span, "`" + name + "` is already defined at line " + prev.span.line(),
                    "ALang has no shadowing; choose a new name such as " + name + "2 or a more descriptive one");
                break;
            }
        }
        checkNameFree(name, span);
        LocalVar v = new LocalVar(name, span, type, mutable, false, ctx.routine.locals.size());
        ctx.routine.locals.add(v);
        ctx.scopes.peek().put(name, v);
        return v;
    }

    private LocalVar lookupLocal(String name) {
        for (Map<String, LocalVar> scope : ctx.scopes) {
            LocalVar v = scope.get(name);
            if (v != null) return v;
        }
        if (ctx.inEnsures && name.equals("result") && ctx.fn != null && ctx.fn.resultVar != null) {
            return ctx.fn.resultVar;
        }
        return null;
    }

    private List<String> visibleValueNames() {
        List<String> out = new ArrayList<>(consts.keySet());
        for (Map<String, LocalVar> s : ctx.scopes) out.addAll(s.keySet());
        return out;
    }

    // =================================================================== statements

    /** Checks a block in a new scope; returns true if it never completes normally. */
    private boolean block(Block b) {
        ctx.scopes.push(new HashMap<>());
        boolean jumps = stmts(b.stmts());
        ctx.scopes.pop();
        return jumps;
    }

    private boolean stmts(List<Stmt> list) {
        boolean jumps = false;
        boolean warned = false;
        for (Stmt s : list) {
            if (jumps && !warned) {
                diags.warning(Code.W0301, s.span, "unreachable statement", "remove it, or move it before the return/break");
                warned = true;
            }
            if (stmt(s)) jumps = true;
        }
        return jumps;
    }

    private boolean stmt(Stmt s) {
        return switch (s) {
            case Let l -> {
                Type t = resolveType(l.typeRef);
                ctx.mutCallAllowed = l.init;
                ctx.stored = l.init;
                Type it = expr(l.init, t);
                ctx.mutCallAllowed = null;
                ctx.stored = null;
                requireAssignable(l.init, it, t);
                l.var = declare(l.name, l.nameSpan, t, l.mutable);
                letInits.put(l.var, l.init);
                yield false;
            }
            case Assign a -> {
                assign(a);
                yield false;
            }
            case If i -> {
                boolean all = i.elseBlock != null;
                for (int k = 0; k < i.conds.size(); k++) {
                    condition(i.conds.get(k), "if");
                    if (!block(i.blocks.get(k))) all = false;
                }
                yield (i.elseBlock == null || block(i.elseBlock)) && all;
            }
            case While w -> {
                condition(w.cond, "while");
                Type lt = expr(w.limit, Type.I64);
                if (!Type.isInt(lt) && !Type.isError(lt)) {
                    diags.error(Code.E0201, w.limit.span, "loop limit must be an integer, found " + lt);
                }
                loop(w, w.body);
                yield false;
            }
            case For f -> {
                forLoop(f);
                yield false;
            }
            case Match m -> match(m);
            case Break b -> {
                if (ctx.loopDepth == 0) diags.error(Code.E0302, b.span, "`break` outside of a loop");
                else if (b.label != null) b.target = labelled(b.label, "break", b.span);
                yield true;
            }
            case Continue c -> {
                if (ctx.loopDepth == 0) diags.error(Code.E0302, c.span, "`continue` outside of a loop");
                else if (c.label != null) c.target = labelled(c.label, "continue", c.span);
                yield true;
            }
            case Return r -> {
                returnStmt(r);
                yield true;
            }
            case Assert a -> {
                condition(a.cond, "assert");
                yield false;
            }
            case ExprStmt e -> exprStmt(e);
        };
    }

    private void loop(Loop l, Block body) {
        if (l.label != null) {
            for (Loop outer : ctx.loops) {
                if (l.label.equals(outer.label)) {
                    diags.error(Code.E0105, l.labelSpan, "label `" + l.label + "` is already used by a loop around this one",
                        "give each nested loop its own label");
                    break;
                }
            }
        }
        ctx.loopDepth++;
        ctx.loops.push(l);
        block(body);
        ctx.loops.pop();
        ctx.loopDepth--;
    }

    /** The loop around this statement labelled {@code label}, or null after reporting that there is none. */
    private Loop labelled(String label, String jump, Span at) {
        List<String> labels = new ArrayList<>();
        for (Loop l : ctx.loops) {
            if (label.equals(l.label)) return l;
            if (l.label != null) labels.add(l.label);
        }
        String close = labels.isEmpty() ? null : didYouMean(label, labels);
        String hint = labels.isEmpty()
            ? "label the loop to " + jump + ": " + label + ": for x in xs { ... " + jump + " " + label + "; }"
            : close != null ? close : "the loops around it are labelled " + String.join(", ", labels);
        diags.error(Code.E0302, at, "`" + jump + " " + label + "` is not inside a loop labelled `" + label + "`", hint);
        return null;
    }

    private void condition(Expr e, String what) {
        Type t = expr(e, Type.BOOL);
        if (!Type.isBool(t) && !Type.isError(t)) {
            String hint = Type.isNumeric(t) ? "there is no truthiness; compare explicitly, e.g. x != 0" : null;
            diags.error(Code.E0211, e.span, "condition of `" + what + "` must be bool, found " + t, hint);
        }
    }

    private void assign(Assign a) {
        Name root = rootName(a.target);
        if (root == null) {
            diags.error(Code.E0206, a.target.span, "cannot assign to this expression");
            return;
        }
        LocalVar v = lookupLocal(root.name);
        if (v == null) {
            if (consts.containsKey(root.name)) {
                diags.error(Code.E0205, root.span, "cannot assign to constant `" + root.name + "`",
                    "copy it into a variable first: var x: T = " + root.name + ";");
            } else if (typeExists(root.name)) {
                diags.error(Code.E0206, root.span, "cannot assign to `" + root.name + "`, it is a type");
            } else {
                unknownName(root);
            }
            expr(a.value, null);
            return;
        }
        Type tt = expr(a.target, null);
        if (!v.mutable) {
            diags.error(Code.E0205, root.span, "cannot assign to `" + v.name + "`, it is immutable",
                immutableHint(v, a.target));
        }
        ctx.mutCallAllowed = a.value;
        if (a.op == null) ctx.stored = a.value;
        Type vt = expr(a.value, tt);
        ctx.mutCallAllowed = null;
        ctx.stored = null;
        if (a.op == null) {
            if (!Type.assignable(vt, tt) && sameKindUnknownCapacity(tt, vt)) {
                // Into a parameter's `_` capacity: say how to copy the value into the very variable.
                String t = exprText(a.target);
                String value = exprText(a.value);
                diags.error(Code.E0201, a.value.span, "type mismatch: expected " + tt + ", found " + vt,
                    tt instanceof Type.Str
                        ? "the capacity of " + tt + " is not known here, so only an empty str is sure to fit; to copy the "
                            + "text, clear it and append: clear(mut " + t + "); append(mut " + t + ", " + value + "); "
                            + "append traps (R0004) if it does not fit"
                        : "the capacity of " + tt + " is not known here, so only an empty list is sure to fit; to copy the "
                            + "elements, clear it and push each one: clear(mut " + t + "); for x in " + value + " { push(mut " + t
                            + ", x); } push traps (R0004) when the list is full");
                return;
            }
            requireAssignable(a.value, vt, tt);
            return;
        }
        if (Type.isError(tt) || Type.isError(vt)) return;
        if (!Type.isNumeric(tt) || (a.op == BinOp.MOD && !Type.isInt(tt))) {
            diags.error(Code.E0203, a.span, "operator `" + a.op.text + "=` is not defined for " + tt);
        } else if (!vt.equals(tt)) {
            mismatch(a.value, vt, tt);
        }
    }

    /** Whether {@code to} is a str or list whose capacity is a parameter's `_`, and {@code from} one of the same kind. */
    private static boolean sameKindUnknownCapacity(Type to, Type from) {
        if (to instanceof Type.Str s) return s.cap() < 0 && from instanceof Type.Str;
        return to instanceof Type.ListT l && l.cap() < 0 && from instanceof Type.ListT f && f.elem().equals(l.elem());
    }

    /** How to change {@code place}, whose root is the immutable {@code v}; the advice depends on what declared it. */
    private String immutableHint(LocalVar v, Expr place) {
        if (ctx.fn != null && ctx.fn.paramVars.contains(v)) {
            String hint = "parameters are read-only; declare it as `" + v.name + ": mut " + v.type + "` (callers then pass `mut x`)";
            // A variable cannot have a `_` capacity, so a parameter with one cannot be copied as it is.
            return Type.hasAnyCapacity(v.type) ? hint : hint + ", or copy it: var " + v.name + "2: " + v.type + " = " + v.name + ";";
        }
        For loop = loopVars.get(v);
        if (loop != null && v == loop.indexVar) {
            return "`" + v.name + "` is the index of each element, which the loop sets; to count something else, "
                + "declare a var before the loop: var count: i64 = 0;";
        }
        if (loop != null && loop.isRange()) {
            String from = exprText(loop.from);
            String to = exprText(loop.to);
            return "`" + v.name + "` steps through the range by itself; to move it yourself, use a while loop instead: var "
                + v.name + ": " + v.type + " = " + from + "; while " + v.name + " < " + to + " limit "
                + (from.equals("0") ? to : "<iterations>") + " { ... " + v.name + " += 1; }";
        }
        if (loop != null) {
            Name coll = rootName(loop.from);
            if (coll != null && lookupLocal(coll.name) != null) {
                String items = exprText(loop.from);
                String index = freshName("i");
                String text = exprText(place);
                String rest = text.startsWith(v.name) ? text.substring(v.name.length()) : "";
                return "`" + v.name + "` is a copy of each element; to change the elements of `" + items
                    + "`, loop over indexes and write `" + items + "[" + index + "]" + rest + "` instead of `"
                    + v.name + rest + "`: for " + index + " in 0..len(" + items + ") { }";
            }
        }
        if ((loop != null || matchBindings.contains(v)) && Type.hasAnyCapacity(v.type)) {
            return "`" + v.name + "` is a read-only copy " + (loop != null ? "of each element" : "of the payload")
                + "; change the element in place through its index instead";
        }
        if (loop != null || matchBindings.contains(v)) {
            String what = loop != null ? "of each element" : "of the payload";
            return "`" + v.name + "` is a read-only copy " + what + "; to change it, copy it into a variable: var "
                + freshName(v.name) + ": " + v.type + " = " + v.name + ";";
        }
        Expr init = letInits.get(v);
        if (init != null && init.isPlace() && Type.isAggregate(init.type) && !(place instanceof Name)) {
            // `let it: Item = items[0]; it.qty += 1;`: with `var`, the change would be made to the copy and lost.
            String from = exprText(init);
            String text = exprText(place);
            String rest = text.startsWith(v.name) ? text.substring(v.name.length()) : "";
            return "`" + v.name + "` is a copy of `" + from + "`; to change `" + from + "` itself, write `" + from + rest
                + "` instead of `" + text + "` (declaring `" + v.name + "` with `var` would change only the copy)";
        }
        return "declare it with `var` instead of `let`";
    }

    /** {@code base}, or else base2, base3, ...: the first that names no local in scope, constant, type or function. */
    private String freshName(String base) {
        String name = base;
        for (int k = 2; lookupLocal(name) != null || consts.containsKey(name) || typeExists(name) || fns.containsKey(name); k++) {
            name = base + k;
        }
        return name;
    }

    private void forLoop(For f) {
        Type varType;
        if (f.isRange()) {
            Type[] ts = sameTypeOperands(f.from, f.to, null);
            varType = ts[0];
            if (!Type.isError(ts[0]) && !Type.isError(ts[1])) {
                if (!Type.isInt(ts[0]) || !Type.isInt(ts[1])) {
                    diags.error(Code.E0201, f.from.span, "range bounds must be integers, found " + ts[0] + " and " + ts[1]);
                    varType = Type.ERROR;
                } else if (!ts[0].equals(ts[1])) {
                    diags.error(Code.E0201, f.to.span, "range bounds have different types: " + ts[0] + " and " + ts[1],
                        "convert one bound, e.g. 0..i64(n)");
                    varType = Type.ERROR;
                }
            }
            if (f.step != null) {
                Type st = expr(f.step, Type.isError(varType) ? Type.I64 : varType);
                if (!Type.isError(st) && !Type.isError(varType) && !st.equals(varType)) {
                    diags.error(Code.E0201, f.step.span, "the step of a range of " + varType + " must be " + varType + ", found " + st,
                        "convert it: step " + varType + "(" + exprText(f.step) + ")");
                } else if (f.step instanceof IntLit lit && lit.value.signum() <= 0) {
                    String hint;
                    if (lit.value.equals(java.math.BigInteger.ONE.negate()) && f.from instanceof IntLit a && f.to instanceof IntLit b) {
                        // `10..0 step -1` counts 10 down to 1: the same values as rev 1..=10.
                        java.math.BigInteger low = f.inclusive ? b.value : b.value.add(java.math.BigInteger.ONE);
                        hint = "to count down from " + a.value + " to " + low + ", go through the range backwards: for " + f.name
                            + " in rev " + low + "..=" + a.value + " { }";
                    } else {
                        hint = "to count down, go through the range backwards: `for i in rev a..b step k` takes the values "
                            + "of a..b step k, the last one first";
                    }
                    diags.error(Code.E0202, f.step.span, "a range's step must be positive, found " + lit.value, hint);
                }
            }
            if (f.indexName != null) {
                diags.error(Code.E0212, f.indexSpan, "a range has no index: its values are the indexes",
                    "write for " + f.name + " in " + exprText(f.from) + (f.inclusive ? "..=" : "..") + exprText(f.to) + " { }");
            }
        } else {
            // split and words in a for loop make one piece at a time: there is no list for them to take a type from.
            Builtin b = f.from instanceof Call c ? Builtin.byName(c.name) : null;
            if (b == Builtin.SPLIT || b == Builtin.WORDS || b == Builtin.CHARS) ctx.pieces = (Call) f.from;
            Type ct = expr(f.from, null);
            ctx.pieces = null;
            varType = Type.elementOf(ct);
            if (varType == null) {
                if (!Type.isError(ct)) {
                    diags.error(Code.E0212, f.from.span, "cannot iterate over " + ct,
                        Type.isInt(ct) ? "to count, use a range: for i in 0..n { }" : "iterate over an array, list or str");
                }
                varType = Type.ERROR;
            }
        }
        if (f.rev && f.pieces() != null) {
            diags.error(Code.E0212, f.from.span, "rev goes through a range, array, list or str backwards, not the pieces of "
                    + f.pieces().name + ", which are made from the front",
                "store them in a list first: let parts: list[str[32]; 16] = " + exprText(f.from) + "; for p in rev parts { }");
        }
        ctx.scopes.push(new HashMap<>());
        if (f.indexName != null) {
            f.indexVar = declare(f.indexName, f.indexSpan, Type.I64, false);
            loopVars.put(f.indexVar, f);
        }
        f.var = declare(f.name, f.nameSpan, varType, false);
        loopVars.put(f.var, f);
        loop(f, f.body);
        ctx.scopes.pop();
        if (!f.isRange()) {
            Expr src = f.source();
            Name root = src.isPlace() ? rootName(src) : null;
            f.snapshot = root != null && root.local != null && Uses.changes(f.body, root.local);
        }
    }

    private void returnStmt(Return r) {
        if (ctx.fn == null) {
            if (r.value != null) {
                diags.error(Code.E0303, r.span, "tests cannot return a value");
                expr(r.value, null);
            }
            return;
        }
        Type ret = ctx.fn.ret;
        if (r.value == null) {
            if (!(ret instanceof Type.Void) && !Type.isError(ret)) {
                diags.error(Code.E0303, r.span, "function `" + ctx.fn.name + "` must return a value of type " + ret);
            }
            return;
        }
        if (ret instanceof Type.Void) {
            diags.error(Code.E0303, r.value.span, "function `" + ctx.fn.name + "` has no return type but returns a value",
                "add a return type: fn " + ctx.fn.name + "(...) -> T");
            expr(r.value, null);
            return;
        }
        ctx.mutCallAllowed = r.value;
        ctx.stored = r.value;
        Type t = expr(r.value, ret);
        ctx.mutCallAllowed = null;
        ctx.stored = null;
        requireAssignable(r.value, t, ret);
    }

    private boolean exprStmt(ExprStmt s) {
        Expr e = s.expr;
        if (e instanceof Call c) {
            ctx.mutCallAllowed = c;
            Type t = expr(c, null);
            ctx.mutCallAllowed = null;
            if (c.builtin == Builtin.PANIC) return true;
            if (!(t instanceof Type.Void) && !Type.isError(t) && c.builtin != Builtin.POP && c.builtin != Builtin.REMOVE) {
                diags.warning(Code.W0302, c.span, "the result of `" + c.name + "` (" + t + ") is not used",
                    "store it: let value: " + t + " = " + c.name + "(...);");
            }
            return false;
        }
        Type t = expr(e, null);
        // `xs.push(1);` and `show(n: 1);` are calls written the wrong way. Their own error already says how
        // to write them, and "only function calls can be used as statements" would mislead.
        if ((e instanceof VariantCall || e instanceof StructLit) && Type.isError(t)) return false;
        String hint = "only function calls can be used as statements";
        if (e instanceof Binary b && b.op == BinOp.EQ) hint = "did you mean assignment `=` instead of `==`?";
        else if (e instanceof Binary b && b.op.isArith()) hint = "did you mean a compound assignment like x " + b.op.text + "= ...?";
        diags.error(Code.E0305, e.span, "this expression has no effect", hint);
        return false;
    }

    // ------------------------------------------------------------------ match

    private boolean match(Match m) {
        Type t = expr(m.subject, null);
        boolean allJump = true;
        boolean wildcard = false;
        Set<Object> seen = new HashSet<>();
        boolean supported = Type.isError(t) || t instanceof Type.EnumT || Type.isInt(t) || Type.isBool(t)
            || t instanceof Type.Str;
        if (!supported) {
            diags.error(Code.E0308, m.subject.span, "cannot match on a value of type " + t,
                "match works on enums, integers, bool and str; use if/else for other types");
        }
        for (Arm arm : m.arms) {
            Pattern p = arm.pattern();
            if (wildcard) {
                diags.error(Code.E0307, p.span, "unreachable arm: `_` above already matches everything",
                    "move `_ => { }` to the end");
            }
            ctx.scopes.push(new HashMap<>());
            switch (p) {
                case WildPat w -> wildcard = true;
                case VariantPat v -> variantPattern(v, t, seen);
                case IntPat i -> {
                    if (supported && !Type.isError(t)) {
                        if (!Type.isInt(t)) {
                            diags.error(Code.E0308, p.span, "integer pattern cannot match a value of type " + t);
                        } else if (!Type.kind(t).fits(i.value)) {
                            diags.error(Code.E0202, p.span, "pattern " + i.value + " is out of range for " + t);
                        } else if (!seen.add(i.value)) {
                            diags.error(Code.E0307, p.span, "duplicate pattern " + i.value);
                        }
                    }
                }
                case StrPat sp -> {
                    if (supported && !Type.isError(t)) {
                        if (!(t instanceof Type.Str)) {
                            diags.error(Code.E0308, p.span, "string pattern cannot match a value of type " + t);
                        } else if (!seen.add(new String(sp.value, java.nio.charset.StandardCharsets.ISO_8859_1))) {
                            diags.error(Code.E0307, p.span, "duplicate pattern");
                        }
                    }
                }
                case BoolPat b -> {
                    if (supported && !Type.isError(t)) {
                        if (!Type.isBool(t)) {
                            diags.error(Code.E0308, p.span, "bool pattern cannot match a value of type " + t);
                        } else if (!seen.add(b.value)) {
                            diags.error(Code.E0307, p.span, "duplicate pattern " + b.value);
                        }
                    }
                }
            }
            if (!block(arm.body())) allJump = false;
            ctx.scopes.pop();
        }
        if (!wildcard && supported && !Type.isError(t)) {
            String missing = null;
            if (t instanceof Type.EnumT et) {
                List<String> names = new ArrayList<>();
                for (Variant v : et.decl().variants) {
                    if (!seen.contains(v)) names.add(et.decl().name + "." + v.name);
                }
                if (!names.isEmpty()) missing = String.join(", ", names);
            } else if (Type.isBool(t)) {
                if (!seen.contains(true)) missing = "true";
                else if (!seen.contains(false)) missing = "false";
            } else {
                missing = "values other than the listed ones";
            }
            if (missing != null) {
                diags.error(Code.E0306, m.span, "match is not exhaustive: missing " + missing,
                    "add arms for them, or add `_ => { }` as the last arm");
            }
        }
        return allJump && !m.arms.isEmpty();
    }

    private void variantPattern(VariantPat v, Type t, Set<Object> seen) {
        if (Type.isError(t)) return;
        if (!(t instanceof Type.EnumT et)) {
            diags.error(Code.E0308, v.span, "enum pattern cannot match a value of type " + t);
            return;
        }
        EnumDecl d = et.decl();
        if (!v.enumName.equals(d.name)) {
            diags.error(Code.E0308, v.span, "pattern of enum `" + v.enumName + "` cannot match a value of type " + d.name,
                "write " + d.name + "." + v.variantName);
            return;
        }
        Variant variant = d.variant(v.variantName);
        if (variant == null) {
            diags.error(Code.E0107, v.span, "enum `" + d.name + "` has no variant `" + v.variantName + "`",
                didYouMean(v.variantName, variantNames(d)));
            return;
        }
        v.variant = variant;
        if (!seen.add(variant)) {
            diags.error(Code.E0307, v.span, "duplicate pattern " + d.name + "." + variant.name);
        }
        int n = variant.payload.size();
        if (n == 0 && v.bindings != null) {
            diags.error(Code.E0308, v.span, d.name + "." + variant.name + " has no payload",
                "write " + d.name + "." + variant.name + " without parentheses");
            return;
        }
        if (n > 0 && (v.bindings == null || v.bindings.size() != n)) {
            diags.error(Code.E0308, v.span, d.name + "." + variant.name + " carries " + n + " value(s); bind each one",
                "write " + d.name + "." + variant.name + "(" + "_, ".repeat(n - 1) + "_) or name them");
            return;
        }
        for (int i = 0; i < n; i++) {
            String b = v.bindings.get(i);
            LocalVar binding = b == null ? null : declare(b, v.bindingSpans.get(i), variant.payload.get(i), false);
            if (binding != null) matchBindings.add(binding);
            v.vars.add(binding);
        }
    }

    private static List<String> variantNames(EnumDecl d) {
        return d.variants.stream().map(v -> v.name).toList();
    }

    // =================================================================== expressions

    /**
     * Type-checks {@code e}. {@code expected} is the type the context wants (or null); it is
     * used to type literals. The caller still verifies assignability.
     */
    private Type expr(Expr e, Type expected) {
        Type t = exprInner(e, expected);
        e.type = t;
        return t;
    }

    private Type exprInner(Expr e, Type expected) {
        return switch (e) {
            case IntLit i -> intLit(i, expected);
            case FloatLit f -> {
                Type t = expected != null && Type.kind(expected) == PrimKind.F32 ? Type.F32 : Type.F64;
                floatLiteralRange(f, f.valueAs(Type.kind(t)), t);
                yield t;
            }
            case StrLit s -> new Type.Str(s.value.length);
            case BoolLit b -> Type.BOOL;
            case Name n -> name(n);
            case FieldAccess f -> fieldAccess(f);
            case Index ix -> index(ix);
            case Call c -> call(c, expected);
            case VariantCall v -> variantCall(v);
            case StructLit s -> structLit(s);
            case Convert c -> convert(c);
            case Resize r -> resize(r);
            case Unary u -> unary(u, expected);
            case Binary b -> binary(b, expected);
            case ArrayLit a -> arrayLit(a, expected);
            case Repeat r -> repeat(r, expected);
        };
    }

    private Type intLit(IntLit i, Type expected) {
        if (expected != null && Type.isInt(expected)) {
            if (!Type.kind(expected).fits(i.value)) {
                diags.error(Code.E0202, i.span, "literal " + i.value + " does not fit in " + expected,
                    expected + " holds " + Type.kind(expected).min() + ".." + Type.kind(expected).max());
            }
            return expected;
        }
        if (expected != null && Type.isFloat(expected)) {
            floatLiteralRange(i, i.floatValue(Type.kind(expected)), expected);
            return expected;
        }
        if (PrimKind.I64.fits(i.value)) return Type.I64;
        if (PrimKind.U64.fits(i.value)) return Type.U64;
        diags.error(Code.E0202, i.span, "literal " + i.value + " does not fit in any integer type");
        return Type.ERROR;
    }

    private Type index(Index ix) {
        Type tt = expr(ix.target, null);
        Type it = expr(ix.index, Type.I64);
        if (!Type.isInt(it) && !Type.isError(it)) {
            diags.error(Code.E0212, ix.index.span, "index must be an integer, found " + it);
        }
        if (Type.isError(tt)) return Type.ERROR;
        Type elem = Type.elementOf(tt);
        if (elem == null) diags.error(Code.E0212, ix.target.span, "cannot index a value of type " + tt);
        return elem == null ? Type.ERROR : elem;
    }

    /** A literal too large for its float type would silently become inf; integer literals get E0202 too. */
    private void floatLiteralRange(Expr lit, double value, Type t) {
        if (Double.isInfinite(value)) {
            diags.error(Code.E0202, lit.span, "literal " + exprText(lit) + " is too large for " + t + " and would become inf",
                Type.kind(t) == PrimKind.F32 ? "f32 holds magnitudes up to about 3.4e38; use f64" : "f64 holds magnitudes up to about 1.8e308");
        }
    }

    private Type name(Name n) {
        LocalVar v = lookupLocal(n.name);
        if (v != null) {
            n.local = v;
            return v.type;
        }
        ConstDecl c = consts.get(n.name);
        if (c != null) {
            ensureConst(c);
            n.constant = c;
            return c.type;
        }
        if (enums.containsKey(n.name)) {
            diags.error(Code.E0201, n.span, "`" + n.name + "` is an enum type, not a value",
                "use one of its variants: " + n.name + "." + enums.get(n.name).variants.get(0).name);
            return Type.ERROR;
        }
        if (structs.containsKey(n.name)) {
            diags.error(Code.E0201, n.span, "`" + n.name + "` is a struct type, not a value",
                "build a value with named fields: " + n.name + "(field: value, ...)");
            return Type.ERROR;
        }
        unknownName(n);
        return Type.ERROR;
    }

    private void unknownName(Name n) {
        if (n.name.equals("result")) {
            diags.error(Code.E0501, n.span, "`result` can only be used in `ensures` of a function that returns a value");
            return;
        }
        String hint = switch (n.name) {
            case "null", "nil", "None", "NULL", "undefined" ->
                "ALang has no null; model absence with an enum, e.g. enum MaybeI32 { None, Some(i32) }";
            case "self", "this" -> "there are no methods or `self`; pass the value as a parameter";
            case "True", "False" -> "booleans are lowercase: true, false";
            default -> fns.containsKey(n.name) || Builtin.byName(n.name) != null
                ? "`" + n.name + "` is a function; call it with parentheses: " + n.name + "(...)"
                : didYouMean(n.name, visibleValueNames());
        };
        diags.error(Code.E0101, n.span, "unknown name `" + n.name + "`", hint);
    }

    private Type fieldAccess(FieldAccess f) {
        if (f.target instanceof Name n && lookupLocal(n.name) == null && !consts.containsKey(n.name)) {
            EnumDecl d = enums.get(n.name);
            if (d != null) {
                Variant v = d.variant(f.name);
                if (v == null) {
                    diags.error(Code.E0107, f.nameSpan, "enum `" + d.name + "` has no variant `" + f.name + "`",
                        didYouMean(f.name, variantNames(d)));
                    return Type.ERROR;
                }
                if (!v.payload.isEmpty()) {
                    diags.error(Code.E0204, f.span, d.name + "." + v.name + " needs " + v.payload.size() + " value(s)",
                        "write " + d.name + "." + v.name + "(" + joinTypes(v.payload) + ")");
                }
                f.variant = v;
                return new Type.EnumT(d);
            }
        }
        Type t = expr(f.target, null);
        if (Type.isError(t)) return Type.ERROR;
        if (t instanceof Type.StructT st) {
            int idx = st.decl().fieldIndex(f.name);
            if (idx < 0) {
                diags.error(Code.E0106, f.nameSpan, "struct `" + st.decl().name + "` has no field `" + f.name + "`",
                    didYouMean(f.name, st.decl().fieldNames()));
                return Type.ERROR;
            }
            f.fieldIndex = idx;
            return st.decl().fields.get(idx).type;
        }
        String hint = null;
        if (Type.elementOf(t) != null) {
            hint = switch (f.name) {
                case "len", "length", "size", "count" -> "use the builtin len(x)";
                case "cap", "capacity" -> "use the builtin cap(x)";
                default -> "arrays, lists and strings have no fields; use builtins such as len(x)";
            };
        }
        diags.error(Code.E0106, f.nameSpan, "a value of type " + t + " has no field `" + f.name + "`", hint);
        return Type.ERROR;
    }

    private static String joinTypes(List<Type> ts) {
        return String.join(", ", ts.stream().map(Type::toString).toList());
    }

    // ------------------------------------------------------------------ calls

    private Type call(Call c, Type expected) {
        Builtin b = Builtin.byName(c.name);
        if (b != null) {
            c.builtin = b;
            Type t = builtin(c, b, expected);
            checkMutCallPosition(c, t);
            checkAliasing(c.args);
            return t;
        }
        FnDecl f = fns.get(c.name);
        if (f == null) {
            for (Arg a : c.args) expr(a.expr(), null);
            if (structs.containsKey(c.name)) {
                diags.error(Code.E0213, c.span, "struct values need named fields",
                    "write " + c.name + "(" + String.join(": ..., ", structs.get(c.name).fieldNames()) + ": ...)");
            } else if (enums.containsKey(c.name)) {
                diags.error(Code.E0201, c.nameSpan, "`" + c.name + "` is an enum type; build a variant instead",
                    "write " + c.name + "." + enums.get(c.name).variants.get(0).name);
            } else {
                diags.error(Code.E0102, c.nameSpan, "unknown function `" + c.name + "`", functionHint(c.name));
            }
            return Type.ERROR;
        }
        c.fn = f;
        signature(f);
        checkMutCallPosition(c, f.ret);
        if (c.args.size() != f.params.size()) {
            if (ctx.routine != null) ctx.routine.callees.add(f);
            diags.error(Code.E0204, c.span, "`" + f.name + "` takes " + f.params.size() + " argument(s) but "
                + c.args.size() + " were given", "signature: " + signatureText(f));
            for (Arg a : c.args) expr(a.expr(), null);
            return f.ret;
        }
        int before = diags.errorCount();
        // What each `_` of the parameters' types stands for in this call.
        Map<Integer, Integer> caps = new HashMap<>();
        for (int i = 0; i < c.args.size(); i++) {
            Arg a = c.args.get(i);
            LocalVar p = f.paramVars.get(i);
            if (p.byRef) {
                if (!a.mut()) {
                    expr(a.expr(), argExpected(p.type, a.expr()));
                    diags.error(Code.E0207, a.span(), "parameter `" + p.name + "` of `" + f.name + "` is `mut`",
                        "pass a variable by reference: mut " + exprText(a.expr()));
                    continue;
                }
                Type at = mutPlace(a.expr());
                if (!Type.bind(p.type, at, true, caps)) {
                    diags.error(Code.E0207, a.span(), "`mut` argument must have exactly type " + p.type + ", found " + at,
                        Type.hasAnyCapacity(p.type)
                            ? "`mut` parameters cannot convert or widen; only the `_` capacities of " + p.type + " take any value"
                            : "`mut` parameters cannot convert or widen; declare the variable as " + p.type);
                }
            } else {
                if (a.mut()) {
                    diags.error(Code.E0207, a.span(), "parameter `" + p.name + "` of `" + f.name + "` is not `mut`",
                        "remove `mut`; the value is passed read-only");
                }
                Type at = expr(a.expr(), argExpected(p.type, a.expr()));
                if (!Type.bind(p.type, at, false, caps)) mismatch(a.expr(), at, p.type);
            }
        }
        checkAliasing(c.args);
        if (f.generic() && diags.errorCount() == before && ctx.routine != null && !(ctx.fn != null && ctx.fn.generic())) {
            // Every capacity of the call is known: run the copy of `f` made for them. In a function that takes
            // any capacity itself they are not known yet; its instances make theirs when they are checked. An
            // argument whose type failed to resolve binds nothing, and the program does not run.
            List<Integer> known = new ArrayList<>();
            int n = anyCapacities(f);
            for (int i = 0; i < n && caps.get(i) != null && caps.get(i) >= 0; i++) known.add(caps.get(i));
            if (known.size() == n) c.fn = instance(f, known);
        }
        if (ctx.routine != null) ctx.routine.callees.add(c.fn);
        return f.ret;
    }

    /**
     * The type an argument is checked against: the parameter's, or for one with `_`s, the type a list or array
     * literal gets from it with its own length in place of the `_`. Other arguments have a type of their own.
     */
    private static Type argExpected(Type param, Expr arg) {
        if (!Type.hasAnyCapacity(param)) return param;
        if (arg instanceof ArrayLit a && !Type.hasAnyCapacity(Type.elementOf(param))) {
            int n = Math.max(1, a.elems.size());
            // An array has at least one element, so `[]` is never one; the literal then reports that it has no type.
            if (param instanceof Type.ArrayT at) {
                return a.elems.isEmpty() ? null : new Type.ArrayT(at.elem(), at.len() < 0 ? a.elems.size() : at.len());
            }
            if (param instanceof Type.ListT lt) return new Type.ListT(lt.elem(), lt.cap() < 0 ? n : lt.cap());
        }
        return null;
    }

    /** How many `_`s the parameters' types of {@code f} have. */
    private static int anyCapacities(FnDecl f) {
        int n = 0;
        for (LocalVar p : f.paramVars) n = Math.max(n, anyCapacities(p.type));
        return n;
    }

    /** One more than the highest index of a `_` in {@code t}, or 0. */
    private static int anyCapacities(Type t) {
        return switch (t) {
            case Type.Str s -> Math.max(0, -s.cap());
            case Type.ListT l -> Math.max(Math.max(0, -l.cap()), anyCapacities(l.elem()));
            case Type.ArrayT a -> Math.max(Math.max(0, -a.len()), anyCapacities(a.elem()));
            default -> 0;
        };
    }

    /** Functions that take any capacity, instantiated but not yet checked (see {@link #instance}). */
    private final List<FnDecl> pendingInstances = new ArrayList<>();

    /**
     * The copy of {@code g}, a function with `_`s in its parameters' types, for the capacities {@code caps}:
     * made on its first call with them, and checked later, after every function of the program. The interpreter,
     * the memory bound and the C backend see only instances, all of whose types are known.
     */
    private FnDecl instance(FnDecl g, List<Integer> caps) {
        FnDecl inst = g.instances.get(caps);
        if (inst != null) return inst;
        inst = g.copy.get();
        inst.origin = g;
        inst.capacities = List.copyOf(caps);
        inst.doc = g.doc;
        g.instances.put(inst.capacities, inst);
        // What the signature reports, it reported for `g` already.
        Diagnostics saved = diags;
        diags = new Diagnostics(saved.source());
        signature(inst);
        diags = saved;
        pendingInstances.add(inst);
        return inst;
    }

    /**
     * Checks the instances made so far, and those they make in turn. An instance has the code of a function
     * that checked without errors, so it has none either; one that turns up all the same is reported.
     */
    private void checkInstances(Set<FnDecl> failed) {
        while (!pendingInstances.isEmpty()) {
            FnDecl inst = pendingInstances.remove(0);
            if (failed.contains(inst.origin)) continue;
            Diagnostics saved = diags;
            diags = new Diagnostics(saved.source());
            body(inst);
            Diagnostics found = diags;
            diags = saved;
            for (Diagnostic d : found.all()) {
                if (d.code().isWarning()) continue;
                diags.add(d.code(), d.span(), d.message() + " (in " + instanceLabel(inst) + ")", d.hint());
            }
        }
    }

    /** How an instance is named in messages and the memory report: lookup(list[Entry; 64], str[1024]). */
    static String instanceLabel(FnDecl f) {
        return f.name + "(" + String.join(", ", f.paramVars.stream().map(v -> v.type.toString()).toList()) + ")";
    }

    private String functionHint(String name) {
        // Names of builtins never get here: they are calls of the builtin (or E0108 when declared).
        return switch (name) {
            case "println", "printf", "printLn", "puts", "console_log", "echo" -> "use print(values...)";
            case "length", "size", "strlen" -> "use len(x)";
            case "capacity" -> "use cap(x)";
            case "format", "sprintf", "concat", "str_cat", "strcat", "to_string", "str", "string" ->
                "build strings with append(mut s, values...) into a str[N] variable";
            case "add", "append_item", "push_back" -> "use push(mut xs, value)";
            case "insert_at", "add_at" -> "use insert(mut xs, i, value)";
            case "remove_at", "delete", "erase", "pop_at" -> "use remove(mut xs, i), which returns the element";
            case "exit", "abort", "fail", "error", "throw", "raise" -> "use panic(\"message\")";
            case "range" -> "use a range loop: for i in 0..n { }, for i in 0..=n step 2 { }, or backwards: for i in rev 0..n { }";
            case "sorted", "sort_by", "sort_by_key", "order_by" ->
                "sort a list in place with sort(mut xs); to order by a key, sort structs whose first field is the key "
                    + "(structs order field by field), and reverse(mut xs) for descending";
            case "reversed", "rev" -> "go through it backwards with for x in rev xs { }, or reverse it in place with reverse(mut xs)";
            case "implode", "join_with", "joined", "concat_with" -> "use join(parts, sep): let line: str[64] = join(parts, \", \");";
            case "includes", "has" -> "use contains(xs, x)";
            case "find", "indexOf", "index", "position", "find_index", "search" -> "use index_of(xs, x), which is -1 when x is absent";
            case "startswith", "startsWith", "has_prefix" -> "use starts_with(s, prefix)";
            case "endswith", "endsWith", "has_suffix" -> "use ends_with(s, suffix)";
            case "substr", "substring", "sub", "mid", "subslice" -> "use slice(s, from, to): the bytes from..to as a copy";
            case "strip", "trim_start", "trim_end", "lstrip", "rstrip", "trim_left", "trim_right", "trimStart", "trimEnd" ->
                "use trim(s), which removes ASCII whitespace at both ends";
            case "upper", "uppercase", "toUpperCase", "toupper" -> "use to_upper(x), for a u8 byte or a str";
            case "lower", "lowercase", "toLowerCase", "tolower" -> "use to_lower(x), for a u8 byte or a str";
            case "capitalize" -> "change the first byte of a var: s[0] = to_upper(s[0]);";
            case "split_whitespace", "split_words", "splitlines", "lines", "tokenize", "strtok" ->
                "use words(s) to split on whitespace, or split(s, sep): let parts: list[str[32]; 16] = split(s, '\\n');";
            case "int", "float", "double", "long", "short", "usize", "isize" ->
                "convert with the target type's name: i64(x), f64(x), u8(x); i64(s) and f64(s) also read a number from text";
            case "parse", "parse_int", "parseInt", "parse_i64", "parse_i32", "atoi", "atol", "stoi", "strtol", "to_int",
                 "toInt", "to_i64", "to_number", "number", "Number" -> Hints.PARSE_NUMBER;
            case "parse_float", "parse_f64", "atof", "parseFloat", "strtod", "to_float", "toFloat" ->
                "use f64(s), checked with is_float(s) first: it traps (R0009) on text that is not a decimal number";
            case "powf", "powi", "fpow" -> "use pow(x, y): on floats it takes a float exponent, pow(2.0, 0.5)";
            case "exp2" -> "use pow(2.0, x)";
            case "ln" -> "use log(x), the natural logarithm";
            case "hypot" -> "use sqrt(x * x + y * y)";
            case "cbrt" -> "use pow(x, 1.0 / 3.0) for x >= 0";
            case "sinh", "cosh", "tanh" -> "write it with exp: sinh(x) is (exp(x) - exp(-x)) / 2.0";
            case "fabs" -> "use abs(x)";
            case "fmin", "fmax" -> "use " + name.substring(1) + "(a, b)";
            case "isdigit", "isDigit" -> "use is_digit(c) on a u8 byte";
            case "isalpha", "isAlpha", "is_letter" -> "use is_alpha(c) on a u8 byte";
            case "isalnum", "is_alphanumeric" -> "use is_alnum(c) on a u8 byte";
            case "isspace", "is_whitespace" -> "use is_space(c) on a u8 byte";
            case "isupper" -> "use is_upper(c) on a u8 byte";
            case "islower" -> "use is_lower(c) on a u8 byte";
            case "isnan" -> "use is_nan(x)";
            case "isfinite" -> "use is_finite(x)";
            case "sum" -> "there is no sum; add in a loop: var total: i64 = 0; for x in xs { total += x; }";
            case "map", "filter", "reduce", "fold", "any", "all", "zip", "foreach", "for_each", "forEach" ->
                "there are no higher-order functions; write the loop: for x in xs { if x > 0 { push(mut out, x); } }";
            case "enumerate" -> "for i, x in xs { } gives each element with its index";
            case "ord" -> "a character is already a byte: s[i] and 'a' are u8 values; use i64(c) for its number";
            case "chr" -> "push(mut s, u8(n)) adds the byte n to a str";
            case "dict", "HashMap", "hashmap", "Map", "set", "Set", "HashSet" -> Hints.COLLECTIONS;
            case "input", "read", "read_line", "readline", "readLine", "scanf", "gets", "getline", "open", "fopen",
                 "read_file", "readFile", "write_file", "writeFile", "fetch", "get_env", "getenv", "env" ->
                "there is no input, file, network or environment access; a program's only inputs are its tool "
                + "arguments: declare `tool fn f(text: str[1024])` and call it with `alang call`";
            case "random", "rand", "randint", "rand_int", "random_int" ->
                "there is no randomness; take a seed as an argument and compute the next value from it: "
                + "seed = (seed * 1103515245 + 12345) % 2147483648; with seed: i64 below 2147483648";
            case "time", "now", "clock", "sleep", "timestamp", "time_now", "current_time" ->
                "there is no clock; take the time as an argument";
            default -> {
                List<String> names = new ArrayList<>(fns.keySet());
                for (Builtin b : Builtin.values()) names.add(b.name);
                names.addAll(structs.keySet());
                yield didYouMean(name, names);
            }
        };
    }

    private static String signatureText(FnDecl f) {
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < f.params.size(); i++) {
            LocalVar v = f.paramVars.get(i);
            ps.add(v.name + ": " + (v.byRef ? "mut " : "") + v.type);
        }
        String ret = f.ret instanceof Type.Void ? "" : " -> " + f.ret;
        return "fn " + f.name + "(" + String.join(", ", ps) + ")" + ret;
    }

    private void checkMutCallPosition(Call c, Type result) {
        if (c.hasMutArg() && ctx.mutCallAllowed != c) {
            String hint = result instanceof Type.Void || Type.isError(result)
                ? "make it a separate statement: " + exprText(c) + ";"
                : "store the result first: let value: " + result + " = " + exprText(c) + "; then use `value`";
            diags.error(Code.E0215, c.span, "a call with `mut` arguments must be its own statement or the whole "
                + "right-hand side of let/var/assignment/return", hint);
        }
    }

    /** Checks a `mut` argument: it must be a mutable place. Returns its type. */
    private Type mutPlace(Expr place) {
        Name root = rootName(place);
        if (root == null) {
            expr(place, null);
            return Type.ERROR;
        }
        LocalVar v = lookupLocal(root.name);
        int before = diags.errorCount();
        Type t = expr(place, null);
        if (v == null) {
            if (consts.containsKey(root.name)) {
                diags.error(Code.E0205, root.span, "a constant cannot be passed as `mut`",
                    "copy it into a variable first: var x: T = " + root.name + ";");
            } else if (diags.errorCount() == before) {
                // An enum variant such as Color.Red type-checks as a value, but it is not storage.
                diags.error(Code.E0206, place.span, "`" + exprText(place) + "` is not a variable and cannot be passed as `mut`",
                    "copy it into a variable first: var x: " + t + " = " + exprText(place) + ";");
            }
            return Type.ERROR;
        }
        if (!v.mutable) {
            diags.error(Code.E0205, root.span, "`" + v.name + "` is immutable and cannot be passed as `mut`",
                immutableHint(v, place));
        }
        return t;
    }

    private static Name rootName(Expr e) {
        while (true) {
            switch (e) {
                case Name n -> {
                    return n;
                }
                case FieldAccess f -> e = f.target;
                case Index i -> e = i.target;
                default -> {
                    return null;
                }
            }
        }
    }

    /**
     * A variable passed as `mut` must not appear in any other argument of the same call that is an
     * aggregate or is itself `mut`: the callee could see it change while it reads it (the C backend passes
     * a read-only aggregate by address), or change it through two parameters, as swap(mut xs[i], mut xs[j])
     * does when i == j. A read-only number or bool is a copy made before the call, so
     * remove(mut xs, len(xs) - 1) is fine; a `mut` one is a reference, not a copy.
     */
    private void checkAliasing(List<Arg> args) {
        for (int i = 0; i < args.size(); i++) {
            Arg a = args.get(i);
            if (!a.mut()) continue;
            Name root = rootName(a.expr());
            if (root == null || root.local == null) continue;
            for (int j = 0; j < args.size(); j++) {
                Arg b = args.get(j);
                Expr other = b.expr();
                boolean copied = !b.mut() && other.type instanceof Type.Prim;
                if (j == i || copied || !mentions(other, root.local)) continue;
                Name otherRoot = b.mut() ? rootName(other) : null;
                if (otherRoot != null && otherRoot.local == root.local) {
                    String place = exprText(other);
                    diags.error(Code.E0208, b.span(), "`" + root.name + "` is passed as `mut` twice in the same call",
                        "the function could change one value through two parameters; copy one into a variable, pass "
                            + "that, and store it back after the call: var copy: " + other.type + " = " + place
                            + "; pass `mut copy` instead of `mut " + place + "`; then " + place + " = copy;");
                } else {
                    diags.error(Code.E0208, b.span(), "`" + root.name + "` is passed as `mut` and also used in "
                        + "another argument of the same call",
                        "copy the value into a separate variable first, then pass the copy");
                }
                return;
            }
        }
    }

    private static boolean mentions(Expr e, LocalVar v) {
        return switch (e) {
            case Name n -> n.local == v;
            case FieldAccess f -> mentions(f.target, v);
            case Index i -> mentions(i.target, v) || mentions(i.index, v);
            case Call c -> c.args.stream().anyMatch(a -> mentions(a.expr(), v));
            case VariantCall c -> c.args.stream().anyMatch(a -> mentions(a.expr(), v));
            case StructLit s -> s.fields.stream().anyMatch(f -> mentions(f.value(), v));
            case Convert c -> mentions(c.arg, v);
            case Resize r -> mentions(r.arg, v);
            case Unary u -> mentions(u.operand, v);
            case Binary b -> mentions(b.left, v) || mentions(b.right, v);
            case ArrayLit a -> a.elems.stream().anyMatch(x -> mentions(x, v));
            case Repeat r -> mentions(r.value, v);
            default -> false;
        };
    }

    private Type builtin(Call c, Builtin b, Type expected) {
        List<Arg> args = c.args;
        switch (b) {
            case PRINT -> {
                for (Arg a : args) printable(a);
                return Type.VOID;
            }
            case LEN, CAP -> {
                if (!arity(c, 1)) return Type.I64;
                Arg a = args.get(0);
                noMut(a);
                Type t = expr(a.expr(), null);
                boolean ok = b == Builtin.LEN ? Type.elementOf(t) != null : (t instanceof Type.ListT || t instanceof Type.Str);
                if (!ok && !Type.isError(t)) {
                    diags.error(Code.E0203, a.span(), b.name + "() needs " + (b == Builtin.LEN ? "an array, list or str" : "a list or str")
                        + ", found " + t);
                }
                return Type.I64;
            }
            case PUSH -> {
                if (!arity(c, 2)) return Type.VOID;
                Type t = collectionArg(c, args.get(0));
                Arg v = args.get(1);
                noMut(v);
                Type elem = Type.isError(t) ? null : Type.elementOf(t);
                Type vt = expr(v.expr(), elem);
                if (elem != null) requireAssignable(v.expr(), vt, elem);
                return Type.VOID;
            }
            case POP -> {
                if (!arity(c, 1)) return Type.ERROR;
                Type t = collectionArg(c, args.get(0));
                return Type.isError(t) ? Type.ERROR : Type.elementOf(t);
            }
            case CLEAR -> {
                if (!arity(c, 1)) return Type.VOID;
                collectionArg(c, args.get(0));
                return Type.VOID;
            }
            case APPEND -> {
                if (args.isEmpty()) {
                    diags.error(Code.E0204, c.span, "append() needs a target string and at least one value",
                        "append(mut s, \"text\", 42)");
                    return Type.VOID;
                }
                Arg target = args.get(0);
                if (!target.mut()) {
                    expr(target.expr(), null);
                    diags.error(Code.E0207, target.span(), "append() modifies its first argument",
                        "write append(mut " + exprText(target.expr()) + ", ...)");
                } else {
                    Type t = mutPlace(target.expr());
                    if (!(t instanceof Type.Str) && !Type.isError(t)) {
                        diags.error(Code.E0203, target.span(), "append() needs a str[N] target, found " + t,
                            t instanceof Type.ListT ? "use push(mut xs, value) for lists" : null);
                    }
                }
                if (args.size() < 2) {
                    diags.error(Code.E0204, c.span, "append() needs at least one value to append");
                }
                for (int i = 1; i < args.size(); i++) printable(args.get(i));
                return Type.VOID;
            }
            case ABS -> {
                if (!arity(c, 1)) return Type.ERROR;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), null);
                if (Type.isError(t)) return t;
                if (!Type.isNumeric(t) || !Type.kind(t).signed) {
                    diags.error(Code.E0203, args.get(0).span(), "abs() needs a signed integer or float, found " + t);
                    return Type.ERROR;
                }
                return t;
            }
            case MIN, MAX -> {
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                noMut(args.get(1));
                Type[] ts = sameTypeOperands(args.get(0).expr(), args.get(1).expr(), Type.isNumeric(expected) ? expected : null);
                if (Type.isError(ts[0]) || Type.isError(ts[1])) return Type.ERROR;
                if (!Type.isNumeric(ts[0]) || !ts[0].equals(ts[1])) {
                    diags.error(Code.E0201, c.span, b.name + "() needs two numbers of the same type, found "
                        + ts[0] + " and " + ts[1]);
                    return Type.ERROR;
                }
                return ts[0];
            }
            case SQRT, FLOOR, CEIL, ROUND, TRUNC, EXP, LOG, LOG2, LOG10, SIN, COS, TAN, ASIN, ACOS, ATAN -> {
                if (!arity(c, 1)) return Type.ERROR;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), Type.F64);
                if (Type.isError(t)) return t;
                if (!Type.isFloat(t)) {
                    diags.error(Code.E0201, args.get(0).span(), b.name + "() needs f32 or f64, found " + t,
                        "convert first: " + b.name + "(f64(x))");
                    return Type.ERROR;
                }
                return t;
            }
            case ATAN2 -> {
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                noMut(args.get(1));
                Type[] ts = sameTypeOperands(args.get(0).expr(), args.get(1).expr(), Type.isFloat(expected) ? expected : Type.F64);
                if (Type.isError(ts[0]) || Type.isError(ts[1])) return Type.ERROR;
                if (!Type.isFloat(ts[0]) || !ts[0].equals(ts[1])) {
                    diags.error(Code.E0201, c.span, "atan2() needs two floats of the same type, found " + ts[0] + " and " + ts[1],
                        "convert them: atan2(f64(y), f64(x))");
                    return Type.ERROR;
                }
                return ts[0];
            }
            case IS_NAN, IS_FINITE -> {
                if (!arity(c, 1)) return Type.BOOL;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), Type.F64);
                if (!Type.isFloat(t) && !Type.isError(t)) {
                    diags.error(Code.E0201, args.get(0).span(), b.name + "() needs f32 or f64, found " + t,
                        Type.isInt(t) ? "an integer is always finite and never NaN" : null);
                }
                return Type.BOOL;
            }
            case CLAMP -> {
                if (!arity(c, 3)) return Type.ERROR;
                for (Arg a : args) noMut(a);
                Type x = sameType(c, args, Type.isNumeric(expected) ? expected : null);
                if (Type.isError(x)) return x;
                if (!Type.isNumeric(x)) {
                    diags.error(Code.E0201, c.span, "clamp() needs three numbers of the same type, found " + joinArgTypes(args));
                    return Type.ERROR;
                }
                return x;
            }
            case POW -> {
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                noMut(args.get(1));
                Expr base = args.get(0).expr();
                Expr power = args.get(1).expr();
                // pow(2, 0.5) is a float pow, as 2 + 0.5 is a float sum.
                Type hint = Type.isNumeric(expected) ? expected : isUntyped(base) && hasFloatLiteral(power) ? Type.F64 : null;
                Type x = expr(base, hint);
                if (Type.isFloat(x)) {
                    Type n = expr(power, x);
                    if (Type.isError(n)) return Type.ERROR;
                    if (!n.equals(x)) {
                        diags.error(Code.E0201, args.get(1).span(), "pow() of an " + x + " needs an exponent of the same type, found " + n,
                            "convert it: pow(" + exprText(base) + ", " + x + "(" + exprText(power) + "))");
                        return Type.ERROR;
                    }
                    return x;
                }
                Type n = expr(power, Type.I64);
                if (Type.isError(x) || Type.isError(n)) return Type.ERROR;
                if (!Type.isInt(x)) {
                    diags.error(Code.E0203, args.get(0).span(), "pow() needs an integer or float base, found " + x);
                    return Type.ERROR;
                }
                if (!Type.isInt(n)) {
                    diags.error(Code.E0203, args.get(1).span(), "pow() of an integer needs an integer exponent, found " + n,
                        Type.isFloat(n) ? "for a float result, make the base a float: pow(f64(" + exprText(base) + "), "
                            + exprText(power) + ")" : null);
                    return Type.ERROR;
                }
                return x;
            }
            case GCD, LCM, WRAPPING_ADD, WRAPPING_SUB, WRAPPING_MUL, SATURATING_ADD, SATURATING_SUB, SATURATING_MUL -> {
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                noMut(args.get(1));
                Type[] ts = sameTypeOperands(args.get(0).expr(), args.get(1).expr(), Type.isInt(expected) ? expected : null);
                if (Type.isError(ts[0]) || Type.isError(ts[1])) return Type.ERROR;
                if (!Type.isInt(ts[0]) || !ts[0].equals(ts[1])) {
                    diags.error(Code.E0201, c.span, b.name + "() needs two integers of the same type, found "
                        + ts[0] + " and " + ts[1]);
                    return Type.ERROR;
                }
                return ts[0];
            }
            case IS_DIGIT, IS_ALPHA, IS_ALNUM, IS_SPACE, IS_UPPER, IS_LOWER -> {
                if (!arity(c, 1)) return Type.BOOL;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), Type.U8);
                if (!t.equals(Type.U8) && !Type.isError(t)) {
                    diags.error(Code.E0201, args.get(0).span(), b.name + "() needs a u8 byte, found " + t,
                        Type.isInt(t) ? "convert it: " + b.name + "(u8(" + exprText(args.get(0).expr()) + "))"
                            : t instanceof Type.Str ? "pass one byte, s[i], or loop over the string: for ch in s { }" : null);
                }
                return Type.BOOL;
            }
            case TO_UPPER, TO_LOWER -> {
                if (!arity(c, 1)) return Type.ERROR;
                noMut(args.get(0));
                Expr e = args.get(0).expr();
                Type t = expr(e, isUntyped(e) ? Type.U8 : null);
                if (Type.isError(t)) return t;
                if (!t.equals(Type.U8) && !(t instanceof Type.Str)) {
                    diags.error(Code.E0201, args.get(0).span(), b.name + "() needs a u8 byte or a str, found " + t);
                    return Type.ERROR;
                }
                return t;
            }
            case CONTAINS, INDEX_OF -> {
                Type ret = b == Builtin.CONTAINS ? Type.BOOL : Type.I64;
                if (!arity(c, 2)) return ret;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), null);
                if (t instanceof Type.Str) {
                    textPattern(b, args.get(1), "what it looks for");
                } else if (t instanceof Type.ArrayT || t instanceof Type.ListT) {
                    noMut(args.get(1));
                    Type elem = Type.elementOf(t);
                    Type x = expr(args.get(1).expr(), elem);
                    if (!Type.comparable(x, elem)) {
                        diags.error(Code.E0201, args.get(1).span(), b.name + "() looks for an element of type " + elem
                            + ", found " + x);
                    }
                } else {
                    expr(args.get(1).expr(), null);
                    if (!Type.isError(t)) {
                        diags.error(Code.E0203, args.get(0).span(), b.name + "() needs a str, array or list, found " + t);
                    }
                }
                return ret;
            }
            case COUNT -> {
                if (args.size() == 1) {
                    diags.error(Code.E0204, c.span, "count() takes 2 argument(s) but 1 were given",
                        "count(xs, x) counts the occurrences of x; the number of elements or bytes is len("
                            + exprText(args.get(0).expr()) + ")");
                    expr(args.get(0).expr(), null);
                    return Type.I64;
                }
                if (!arity(c, 2)) return Type.I64;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), null);
                if (t instanceof Type.Str) {
                    textPattern(b, args.get(1), "what it counts");
                } else if (t instanceof Type.ArrayT || t instanceof Type.ListT) {
                    noMut(args.get(1));
                    Type elem = Type.elementOf(t);
                    Type x = expr(args.get(1).expr(), elem);
                    if (!Type.comparable(x, elem)) {
                        diags.error(Code.E0201, args.get(1).span(), "count() counts elements of type " + elem + ", found " + x);
                    }
                } else {
                    expr(args.get(1).expr(), null);
                    if (!Type.isError(t)) {
                        diags.error(Code.E0203, args.get(0).span(), "count() needs a str, array or list, found " + t);
                    }
                }
                return Type.I64;
            }
            case STARTS_WITH, ENDS_WITH -> {
                if (!arity(c, 2)) return Type.BOOL;
                textArg(b, args.get(0));
                textPattern(b, args.get(1), b == Builtin.STARTS_WITH ? "the prefix" : "the suffix");
                return Type.BOOL;
            }
            case SLICE -> {
                if (!arity(c, 3)) return Type.ERROR;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), null);
                for (int i = 1; i < 3; i++) indexArg(b, args.get(i));
                if (Type.isError(t)) return t;
                if (t instanceof Type.ArrayT a) return new Type.ListT(a.elem(), a.len());
                if (t instanceof Type.Str || t instanceof Type.ListT) return t;
                diags.error(Code.E0203, args.get(0).span(), "slice() needs a str, list or array, found " + t);
                return Type.ERROR;
            }
            case TRIM -> {
                if (!arity(c, 1)) return Type.ERROR;
                return textArg(b, args.get(0));
            }
            case IS_INT, IS_FLOAT -> {
                if (!arity(c, 1)) return Type.BOOL;
                textArg(b, args.get(0));
                return Type.BOOL;
            }
            case SPLIT, WORDS -> {
                if (b == Builtin.SPLIT && args.size() == 1) {
                    diags.error(Code.E0204, c.span, "split() takes 2 argument(s) but 1 were given",
                        "split(s, sep) needs a separator; to split on whitespace, use words(" + exprText(args.get(0).expr()) + ")");
                    expr(args.get(0).expr(), null);
                    return Type.ERROR;
                }
                if (!arity(c, b == Builtin.SPLIT ? 2 : 1)) return Type.ERROR;
                Type text = textArg(b, args.get(0));
                if (b == Builtin.SPLIT) textPattern(b, args.get(1), "the separator");
                if (ctx.pieces == c) {
                    // Each piece is a str of the capacity of the text, which no piece can outgrow. The list
                    // type is nominal: the loop never builds it.
                    return text instanceof Type.Str s ? new Type.ListT(s, s.cap() < 0 ? s.cap() : s.cap() + 1) : Type.ERROR;
                }
                Type stored = storedType(c, b, expected);
                if (text instanceof Type.Str s && stored instanceof Type.ListT l && l.elem() instanceof Type.Str piece) {
                    warnSplitFit(c, b, s, l, piece);
                }
                return stored;
            }
            case IS_UTF8 -> {
                if (!arity(c, 1)) return Type.BOOL;
                textArg(b, args.get(0));
                return Type.BOOL;
            }
            case CHAR_COUNT -> {
                if (!arity(c, 1)) return Type.I64;
                textArg(b, args.get(0));
                return Type.I64;
            }
            case CHARS -> {
                if (!arity(c, 1)) return Type.ERROR;
                Type text = textArg(b, args.get(0));
                if (ctx.pieces == c) {
                    // One code point at a time; the list type is nominal, as for split in a loop.
                    return text instanceof Type.Str s ? new Type.ListT(Type.U32, s.cap() < 0 ? s.cap() : s.cap() + 1) : Type.ERROR;
                }
                return storedType(c, b, expected);
            }
            case PUSH_CHAR -> {
                if (!arity(c, 2)) return Type.VOID;
                Arg target = args.get(0);
                if (!target.mut()) {
                    expr(target.expr(), null);
                    diags.error(Code.E0207, target.span(), "push_char() modifies its first argument",
                        "write push_char(mut " + exprText(target.expr()) + ", c)");
                } else {
                    Type t = mutPlace(target.expr());
                    if (!(t instanceof Type.Str) && !Type.isError(t)) {
                        diags.error(Code.E0203, target.span(), "push_char() needs a str[N] target, found " + t,
                            t instanceof Type.ListT ? "use push(mut xs, value) for lists" : null);
                    }
                }
                Arg v = args.get(1);
                noMut(v);
                Type vt = expr(v.expr(), Type.U32);
                if (!Type.isInt(vt) && !Type.isError(vt)) {
                    diags.error(Code.E0201, v.span(), "push_char() needs a code point, an integer, found " + vt,
                        vt instanceof Type.Str ? "to add text, use append(mut s, " + exprText(v.expr()) + ")" : null);
                }
                return Type.VOID;
            }
            case SLICE_CHARS -> {
                if (!arity(c, 3)) return Type.ERROR;
                Type t = textArg(b, args.get(0));
                for (int i = 1; i < 3; i++) indexArg(b, args.get(i));
                return t;
            }
            case TRY_SPLIT, TRY_WORDS -> {
                boolean split = b == Builtin.TRY_SPLIT;
                if (!arity(c, split ? 3 : 2)) return Type.BOOL;
                Arg target = args.get(0);
                Type t;
                if (!target.mut()) {
                    t = expr(target.expr(), null);
                    diags.error(Code.E0207, target.span(), b.name + "() fills its first argument",
                        "write " + b.name + "(mut " + exprText(target.expr()) + ", ...)");
                } else {
                    t = mutPlace(target.expr());
                }
                if (!Type.isError(t) && !(t instanceof Type.ListT l && l.elem() instanceof Type.Str)) {
                    diags.error(Code.E0203, target.span(), b.name + "() fills a list of str, found " + t,
                        "declare one: var parts: list[str[32]; 16] = [];");
                }
                textArg(b, args.get(1));
                if (split) textPattern(b, args.get(2), "the separator");
                return Type.BOOL;
            }
            case JOIN -> {
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), null);
                if (!Type.isError(t) && !((t instanceof Type.ArrayT || t instanceof Type.ListT) && Type.elementOf(t) instanceof Type.Str)) {
                    diags.error(Code.E0203, args.get(0).span(), "join() needs an array or list of str, found " + t);
                }
                textPattern(b, args.get(1), "the separator");
                return storedType(c, b, expected);
            }
            case REPLACE -> {
                if (!arity(c, 3)) return Type.ERROR;
                textArg(b, args.get(0));
                textPattern(b, args.get(1), "what it replaces");
                textPattern(b, args.get(2), "the replacement");
                return storedType(c, b, expected);
            }
            case SORT, REVERSE -> {
                if (!arity(c, 1)) return Type.VOID;
                sequenceArg(c, args.get(0));
                return Type.VOID;
            }
            case INSERT -> {
                if (!arity(c, 3)) return Type.VOID;
                Type t = collectionArg(c, args.get(0));
                indexArg(b, args.get(1));
                Arg v = args.get(2);
                noMut(v);
                Type elem = Type.isError(t) ? null : Type.elementOf(t);
                Type vt = expr(v.expr(), elem);
                if (elem != null) requireAssignable(v.expr(), vt, elem);
                return Type.VOID;
            }
            case REMOVE -> {
                if (!arity(c, 2)) return Type.ERROR;
                Type t = collectionArg(c, args.get(0));
                indexArg(b, args.get(1));
                return Type.isError(t) ? Type.ERROR : Type.elementOf(t);
            }
            case FIXED -> {
                boolean asText = ctx.text == c;
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                Type x = expr(args.get(0).expr(), Type.F64);
                if (!Type.isFloat(x) && !Type.isError(x)) {
                    diags.error(Code.E0201, args.get(0).span(), "fixed() needs an f32 or f64, found " + x,
                        Type.isInt(x) ? "convert it: fixed(f64(" + exprText(args.get(0).expr()) + "), "
                            + exprText(args.get(1).expr()) + "), or pad an integer with pad_left(n, width, '0')" : null);
                }
                intArg(b, args.get(1), "the number of decimals");
                return formatted(c, b, expected, asText);
            }
            case PAD_LEFT, PAD_RIGHT -> {
                boolean asText = ctx.text == c;
                if (!arity(c, 3)) return Type.ERROR;
                Arg v = args.get(0);
                noMut(v);
                warnCharLiteral(v);
                Expr outer = ctx.text;
                ctx.text = v.expr();
                Type vt = expr(v.expr(), null);
                ctx.text = outer;
                if (!Type.isNumeric(vt) && !Type.isBool(vt) && !(vt instanceof Type.Str) && !Type.isError(vt)) {
                    diags.error(Code.E0203, v.span(), b.name + "() pads a number, bool or str, found " + vt,
                        vt instanceof Type.Void ? null : "pad one field or element, or append the value to a str first");
                }
                intArg(b, args.get(1), "the width");
                Arg fill = args.get(2);
                noMut(fill);
                Type ft = expr(fill.expr(), Type.U8);
                if (!ft.equals(Type.U8) && !Type.isError(ft)) {
                    diags.error(Code.E0201, fill.span(), b.name + "() fills with one u8 byte, found " + ft,
                        ft instanceof Type.Str ? "write a character: ' ' or '0'" : "convert it: u8(" + exprText(fill.expr()) + ")");
                }
                return formatted(c, b, expected, asText);
            }
            case PANIC -> {
                if (!arity(c, 1)) return Type.VOID;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr(), null);
                if (!(t instanceof Type.Str) && !Type.isError(t)) {
                    diags.error(Code.E0201, args.get(0).span(), "panic() needs a string message, found " + t);
                }
                return Type.VOID;
            }
        }
        throw new IllegalStateException();
    }

    private void printable(Arg a) {
        noMut(a);
        warnCharLiteral(a);
        Expr outer = ctx.text;
        ctx.text = a.expr();
        Type t = expr(a.expr(), null);
        ctx.text = outer;
        if (t instanceof Type.Void) {
            diags.error(Code.E0201, a.span(), "cannot print a call that returns nothing");
        }
    }

    private void warnCharLiteral(Arg a) {
        if (a.expr() instanceof IntLit lit && lit.isChar) {
            diags.warning(Code.W0303, a.span(), "character literal " + exprText(a.expr()) + " is the number " + lit.value
                + " and prints as digits", "to add the character to a str use push(mut s, " + exprText(a.expr()) + ")");
        }
    }

    /** An integer argument, such as a width or a number of decimals. */
    private void intArg(Builtin b, Arg a, String what) {
        noMut(a);
        Type t = expr(a.expr(), Type.I64);
        if (!Type.isInt(t) && !Type.isError(t)) {
            diags.error(Code.E0201, a.span(), b.name + "() needs an integer for " + what + ", found " + t);
        }
    }

    /**
     * The type of fixed, pad_left and pad_right: written out by print or append, or padded, they build no str
     * and have a nominal str[0]; otherwise they take the type of the variable they are stored in.
     */
    private Type formatted(Call c, Builtin b, Type expected, boolean asText) {
        if (asText) {
            c.asText = true;
            return new Type.Str(0);
        }
        return storedType(c, b, expected);
    }

    private boolean arity(Call c, int n) {
        if (c.args.size() == n) return true;
        Builtin b = c.builtin;
        diags.error(Code.E0204, c.span, b.name + "() takes " + n + " argument(s) but " + c.args.size() + " were given",
            "signature: " + b.signature);
        for (Arg a : c.args) expr(a.expr(), null);
        return false;
    }

    private void noMut(Arg a) {
        if (a.mut()) diags.error(Code.E0207, a.span(), "this argument is not modified; remove `mut`");
    }

    /**
     * Checks operands that share a type, the way {@link #sameTypeOperands} checks two: the first that is not
     * a bare literal (or, among literals, the first float literal) gives the type, else {@code hint}.
     * Returns the type, or ERROR after reporting operands of different types.
     */
    private Type sameType(Call c, List<Arg> args, Type hint) {
        int anchor = 0;
        for (int i = 0; i < args.size(); i++) {
            Expr e = args.get(i).expr();
            if (!isUntyped(e)) {
                anchor = i;
                break;
            }
            if (hint == null && hasFloatLiteral(e) && !hasFloatLiteral(args.get(anchor).expr())) anchor = i;
        }
        Type t = expr(args.get(anchor).expr(), hint);
        Type[] ts = new Type[args.size()];
        ts[anchor] = t;
        for (int i = 0; i < args.size(); i++) {
            if (i != anchor) ts[i] = expr(args.get(i).expr(), Type.isError(t) ? hint : t);
        }
        for (Type x : ts) {
            if (Type.isError(x)) return Type.ERROR;
        }
        for (Type x : ts) {
            if (!x.equals(t)) {
                diags.error(Code.E0201, c.span, c.name + "() needs operands of the same type, found " + joinArgTypes(args),
                    "convert them to one type, e.g. " + t + "(...)");
                return Type.ERROR;
            }
        }
        return t;
    }

    private static String joinArgTypes(List<Arg> args) {
        return String.join(", ", args.stream().map(a -> String.valueOf(a.expr().type)).toList());
    }

    /** A read-only str argument; returns its type, or ERROR after reporting another type. */
    private Type textArg(Builtin b, Arg a) {
        noMut(a);
        Type t = expr(a.expr(), null);
        if (Type.isError(t) || t instanceof Type.Str) return t;
        diags.error(Code.E0203, a.span(), b.name + "() needs a str, found " + t);
        return Type.ERROR;
    }

    /** What a text function looks for, splits on or puts in: a str or a single u8 byte (a character literal). */
    private void textPattern(Builtin b, Arg a, String what) {
        noMut(a);
        Type t = expr(a.expr(), isUntyped(a.expr()) ? Type.U8 : null);
        if (!Type.isError(t) && !(t instanceof Type.Str) && !t.equals(Type.U8)) {
            diags.error(Code.E0201, a.span(), b.name + "() needs a str or a u8 byte for " + what + ", found " + t,
                Type.isInt(t) ? "convert it: u8(" + exprText(a.expr()) + ")" : null);
        }
    }

    /** A position: an integer of any type, like an index. */
    private void indexArg(Builtin b, Arg a) {
        noMut(a);
        Type t = expr(a.expr(), Type.I64);
        if (!Type.isInt(t) && !Type.isError(t)) {
            diags.error(Code.E0212, a.span(), b.name + "() needs an integer position, found " + t);
        }
    }

    /**
     * The type of split, words, join and replace: that of the variable they initialize or are assigned to,
     * or of the function's return value, since the capacity of what they build cannot be computed.
     */
    private Type storedType(Call c, Builtin b, Type expected) {
        boolean list = b == Builtin.SPLIT || b == Builtin.WORDS || b == Builtin.CHARS;
        String example = switch (b) {
            case SPLIT -> "let parts: list[str[32]; 16] = ";
            case WORDS -> "let ws: list[str[32]; 16] = ";
            case CHARS -> "let cs: list[u32; 64] = ";
            case JOIN -> "let line: str[256] = ";
            case FIXED, PAD_LEFT, PAD_RIGHT -> "let text: str[32] = ";
            default -> "let text: str[256] = ";
        } + exprText(c) + ";";
        if (b.formats() && (ctx.stored != c || expected == null)) {
            diags.error(Code.E0216, c.span, b.name + "() makes text: write it out, or store it in a typed str",
                "pass it to print or append, as in append(mut s, " + exprText(c) + "), or make it the whole right-hand "
                    + "side of a typed let/var, an assignment or a return: " + example);
            return Type.ERROR;
        }
        if (expected != null && Type.isError(expected)) return Type.ERROR;
        if (ctx.stored != c || expected == null) {
            diags.error(Code.E0216, c.span, b.name + "() takes its type, and so its capacity, from the variable it is stored in",
                "make the call the whole right-hand side of a typed let/var, an assignment or a return: " + example
                    + (b == Builtin.CHARS ? " or, to go through the code points one at a time without a list: for c in " + exprText(c) + " { }"
                    : list ? " or, to go through the pieces one at a time without a list: for p in " + exprText(c) + " { }" : ""));
            return Type.ERROR;
        }
        boolean ok = b == Builtin.CHARS ? expected instanceof Type.ListT l && l.elem().equals(Type.U32)
            : list ? expected instanceof Type.ListT l && l.elem() instanceof Type.Str : expected instanceof Type.Str;
        if (!ok) {
            diags.error(Code.E0201, c.span, b.name + "() builds a " + (b == Builtin.CHARS ? "list[u32; M]" : list ? "list[str[K]; M]" : "str[N]")
                + ", but " + expected + " is expected", "store it in a variable of that type: " + example);
            return Type.ERROR;
        }
        return expected;
    }

    /**
     * W0307: split or words of text that is not a constant, stored in a list its pieces may not fit. A str[K]
     * splits into up to K / L + 1 pieces on a separator of L bytes (L is 1 unless it is a literal), and into up
     * to (K + 1) / 2 words, each of up to K bytes. Test blocks run on the text they choose, and are left alone.
     */
    private void warnSplitFit(Call c, Builtin b, Type.Str text, Type.ListT list, Type.Str piece) {
        Expr source = c.args.get(0).expr();
        if (isConst(source) || ctx.routine instanceof TestDecl) return;
        boolean words = b == Builtin.WORDS;
        long k = text.cap();
        String from;
        if (k < 0) {
            from = "from a " + text + " of any length it can make any number of pieces of any length";
        } else {
            long sep = !words && c.args.get(1).expr() instanceof StrLit lit ? Math.max(1, lit.value.length) : 1;
            long pieces = words ? (k + 1) / 2 : k / sep + 1;
            if (pieces <= list.cap() && k <= piece.cap()) return;
            from = "from a " + text + " it can make up to " + pieces + (words ? " words" : " pieces") + " of up to " + k + " bytes";
        }
        String target = words ? "ws" : "parts";
        String rest = c.args.size() > 1 ? ", " + exprText(c.args.get(1).expr()) : "";
        diags.warning(Code.W0307, c.span, b.name + "() may not fit " + list + ": " + from + ", and one that does not fit "
                + "traps (R0004)",
            "to handle any text, use try_" + b.name + ", which returns false instead of trapping: var " + target + ": " + list
                + " = []; let ok: bool = try_" + b.name + "(mut " + target + ", " + exprText(source) + rest
                + "); or go through the pieces one at a time: for p in " + exprText(c) + " { }");
    }

    /** The argument of sort and reverse: a `mut` list, array or str. */
    private Type sequenceArg(Call c, Arg a) {
        if (!a.mut()) {
            Type t = expr(a.expr(), null);
            diags.error(Code.E0207, a.span(), c.name + "() modifies its argument", "write " + c.name + "(mut " + exprText(a.expr()) + ")");
            return t;
        }
        Type t = mutPlace(a.expr());
        if (Type.elementOf(t) == null && !Type.isError(t)) {
            diags.error(Code.E0203, a.span(), c.name + "() needs a list, array or str, found " + t);
            return Type.ERROR;
        }
        return t;
    }

    /** First argument of push/pop/clear: `mut` list or str. */
    private Type collectionArg(Call c, Arg a) {
        if (!a.mut()) {
            Type t = expr(a.expr(), null);
            diags.error(Code.E0207, a.span(), c.name + "() modifies its first argument",
                "write " + c.name + "(mut " + exprText(a.expr()) + switch (c.builtin) {
                    case PUSH -> ", value)";
                    case INSERT -> ", i, value)";
                    case REMOVE -> ", i)";
                    default -> ")";
                });
            return t instanceof Type.ListT || t instanceof Type.Str ? t : Type.ERROR;
        }
        Type t = mutPlace(a.expr());
        if (!(t instanceof Type.ListT) && !(t instanceof Type.Str) && !Type.isError(t)) {
            diags.error(Code.E0203, a.span(), c.name + "() needs a list or str, found " + t,
                t instanceof Type.ArrayT ? "arrays have a fixed length; use list[T; N] for a growable collection" : null);
            return Type.ERROR;
        }
        return t;
    }

    /** Whether `name(...)` takes its first argument as `mut`: push, pop, clear, append, or a function declared so. */
    private boolean modifiesFirstArgument(String name) {
        Builtin b = Builtin.byName(name);
        if (b != null) return b.modifiesFirst();
        FnDecl f = fns.get(name);
        return f != null && !f.params.isEmpty() && f.params.get(0).mut();
    }

    private Type variantCall(VariantCall v) {
        EnumDecl d = enums.get(v.enumName);
        if (d == null) {
            for (Arg a : v.args) expr(a.expr(), null);
            if (lookupLocal(v.enumName) != null || consts.containsKey(v.enumName)) {
                String args = v.args.isEmpty() ? "" : ", ...";
                String mut = modifiesFirstArgument(v.variantName) ? "mut " : "";
                diags.error(Code.E0102, v.span, "there are no methods: `" + v.enumName + "." + v.variantName + "(...)` is not valid",
                    "pass the value as the first argument: " + v.variantName + "(" + mut + v.enumName + args + ")");
            } else {
                diags.error(Code.E0103, v.span, "unknown enum `" + v.enumName + "`",
                    didYouMean(v.enumName, new ArrayList<>(enums.keySet())));
            }
            return Type.ERROR;
        }
        v.enumDecl = d;
        Variant variant = d.variant(v.variantName);
        if (variant == null) {
            for (Arg a : v.args) expr(a.expr(), null);
            diags.error(Code.E0107, v.span, "enum `" + d.name + "` has no variant `" + v.variantName + "`",
                didYouMean(v.variantName, variantNames(d)));
            return Type.ERROR;
        }
        v.variant = variant;
        if (v.args.size() != variant.payload.size()) {
            for (Arg a : v.args) expr(a.expr(), null);
            String hint = variant.payload.isEmpty() ? "write " + d.name + "." + variant.name + " without parentheses"
                : "write " + d.name + "." + variant.name + "(" + joinTypes(variant.payload) + ")";
            diags.error(Code.E0204, v.span, d.name + "." + variant.name + " takes " + variant.payload.size()
                + " value(s) but " + v.args.size() + " were given", hint);
            return new Type.EnumT(d);
        }
        for (int i = 0; i < v.args.size(); i++) {
            Arg a = v.args.get(i);
            noMut(a);
            Type pt = variant.payload.get(i);
            Type at = expr(a.expr(), pt);
            requireAssignable(a.expr(), at, pt);
        }
        return new Type.EnumT(d);
    }

    private Type structLit(StructLit s) {
        StructDecl d = structs.get(s.name);
        if (d == null) {
            for (FieldInit f : s.fields) expr(f.value(), null);
            if (fns.containsKey(s.name) || Builtin.byName(s.name) != null) {
                diags.error(Code.E0213, s.span, "`" + s.name + "` is a function; named arguments are only used to build structs",
                    "pass arguments by position: " + s.name + "(a, b)");
            } else {
                diags.error(Code.E0103, s.span, "unknown struct `" + s.name + "`",
                    didYouMean(s.name, new ArrayList<>(structs.keySet())));
            }
            return Type.ERROR;
        }
        s.decl = d;
        int[] order = new int[d.fields.size()];
        java.util.Arrays.fill(order, -1);
        for (int i = 0; i < s.fields.size(); i++) {
            FieldInit fi = s.fields.get(i);
            int idx = d.fieldIndex(fi.name());
            if (idx < 0) {
                diags.error(Code.E0106, fi.span(), "struct `" + d.name + "` has no field `" + fi.name() + "`",
                    didYouMean(fi.name(), d.fieldNames()));
                expr(fi.value(), null);
                continue;
            }
            if (order[idx] >= 0) {
                diags.error(Code.E0213, fi.span(), "field `" + fi.name() + "` is given twice");
            }
            order[idx] = i;
            Type ft = d.fields.get(idx).type;
            Type vt = expr(fi.value(), ft);
            requireAssignable(fi.value(), vt, ft);
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            if (order[i] < 0) missing.add(d.fields.get(i).name);
        }
        if (!missing.isEmpty()) {
            diags.error(Code.E0213, s.span, "missing field(s) " + String.join(", ", missing) + " in " + d.name + "(...)",
                "every field must be given: " + String.join(": ..., ", missing) + ": ...");
        }
        s.order = order;
        return new Type.StructT(d);
    }

    private Type convert(Convert c) {
        Type target = Type.prim(c.target);
        Type t = expr(c.arg, null);
        if (Type.isError(t)) return target;
        if (c.target == PrimKind.BOOL) {
            diags.error(Code.E0214, c.span, "nothing converts to bool", "compare instead: x != 0");
            return target;
        }
        if (t instanceof Type.Str) {
            // i64("42") and f64("2.5") trap unless the text is a decimal integer that fits, or a decimal number
            // that converts exactly; is_int and is_float say so first.
            if (c.target == PrimKind.F32) {
                diags.error(Code.E0214, c.span, "cannot convert " + t + " to f32",
                    "text converts to f64, then to f32: f32(f64(" + exprText(c.arg) + ")), checked with is_float("
                        + exprText(c.arg) + ") first");
            }
            return target;
        }
        if (!(t instanceof Type.Prim)) {
            diags.error(Code.E0214, c.span, "cannot convert " + t + " to " + target,
                "conversions work between numbers, from bool to integers, and from a str to integers and f64");
            return target;
        }
        if (Type.isBool(t) && c.target.isFloat) {
            diags.error(Code.E0214, c.span, "cannot convert bool to " + target, "convert to an integer first");
            return target;
        }
        if (c.arg instanceof IntLit i && c.target.isInt() && !c.target.fits(i.value)) {
            diags.error(Code.E0202, c.arg.span, "literal " + i.value + " does not fit in " + target);
        }
        return target;
    }

    /**
     * `str[N](s)` and `list[T; N](xs)`: a str of any capacity, or a list of the same element type, with
     * capacity N. A longer value traps (R0004) when the program runs.
     */
    private Type resize(Resize r) {
        Type target = resolveType(r.targetRef);
        Type t = expr(r.arg, Type.isError(target) ? null : target);
        if (Type.isError(target) || Type.isError(t)) return target;
        if (target instanceof Type.Str) {
            if (!(t instanceof Type.Str)) {
                diags.error(Code.E0214, r.span, "cannot convert " + t + " to " + target,
                    target + "(s) changes the capacity of a str; to turn a value into text, append it: var s: " + target
                        + " = \"\"; append(mut s, " + exprText(r.arg) + ");");
            }
            return target;
        }
        Type elem = Type.elementOf(target);
        if (t instanceof Type.ListT l && l.elem().equals(elem)) return target;
        String hint;
        if (t instanceof Type.ArrayT a && a.elem().equals(elem)) {
            hint = "an array is not a list; slice(" + exprText(r.arg) + ", 0, "
                + (a.len() < 0 ? "len(" + exprText(r.arg) + ")" : String.valueOf(a.len())) + ") makes a list of its elements";
        } else if (t instanceof Type.ListT l) {
            hint = "the conversion changes only the capacity, and the elements are " + l.elem() + ", not " + elem
                + "; to convert each element, push them into a " + target + " in a loop";
        } else {
            hint = target + "(xs) changes the capacity of a list of " + elem;
        }
        diags.error(Code.E0214, r.span, "cannot convert " + t + " to " + target, hint);
        return target;
    }

    private Type unary(Unary u, Type expected) {
        switch (u.op) {
            case NOT -> {
                Type t = expr(u.operand, Type.BOOL);
                if (!Type.isBool(t) && !Type.isError(t)) {
                    diags.error(Code.E0211, u.operand.span, "`not` needs a bool, found " + t,
                        Type.isInt(t) ? "for bitwise complement use ~x" : null);
                    return Type.ERROR;
                }
                return Type.BOOL;
            }
            case NEG -> {
                Type t = expr(u.operand, Type.isNumeric(expected) ? expected : null);
                if (Type.isError(t)) return t;
                if (!Type.isNumeric(t) || !Type.kind(t).signed) {
                    diags.error(Code.E0203, u.span, "unary `-` needs a signed integer or float, found " + t);
                    return Type.ERROR;
                }
                return t;
            }
            default -> {
                Type t = expr(u.operand, Type.isInt(expected) ? expected : null);
                if (Type.isError(t)) return t;
                if (!Type.isInt(t)) {
                    diags.error(Code.E0203, u.span, "`~` needs an integer, found " + t,
                        Type.isBool(t) ? "for logical negation use `not`" : null);
                    return Type.ERROR;
                }
                return t;
            }
        }
    }

    /** True for literals and operators on literals, whose type comes from context. */
    private static boolean isUntyped(Expr e) {
        return switch (e) {
            case IntLit i -> true;
            case FloatLit f -> true;
            case Unary u -> u.op != UnOp.NOT && isUntyped(u.operand);
            case Binary b -> (b.op.isArith() || b.op.isBitwise() || b.op.isShift()) && isUntyped(b.left)
                && (b.op.isShift() || isUntyped(b.right));
            default -> false;
        };
    }

    /** An array literal, [a, b] or [v; n], which can also build a list of the type its context expects. */
    private static boolean isArrayLiteral(Expr e) {
        return e instanceof ArrayLit || e instanceof Repeat;
    }

    /** True if an untyped expression contains a float literal, so that it must be a float. */
    private static boolean hasFloatLiteral(Expr e) {
        return switch (e) {
            case FloatLit f -> true;
            case Unary u -> hasFloatLiteral(u.operand);
            case Binary b -> hasFloatLiteral(b.left) || hasFloatLiteral(b.right);
            default -> false;
        };
    }

    /**
     * Checks two operands that should share a type, typing literals from the other side. When both
     * sides are literals and nothing else gives the type, a float literal decides it, so that
     * {@code 2 + 1.5} types like {@code 1.5 + 2}. An array literal takes its type from the other side
     * too, so that {@code [1, 2] == xs} types like {@code xs == [1, 2]} when {@code xs} is a list.
     */
    private Type[] sameTypeOperands(Expr l, Expr r, Type hint) {
        Type lt;
        Type rt;
        boolean rightFirst = isUntyped(l)
            && (!isUntyped(r) || hint == null && !hasFloatLiteral(l) && hasFloatLiteral(r))
            || isArrayLiteral(l) && !isArrayLiteral(r) && !isUntyped(r);
        if (rightFirst) {
            rt = expr(r, hint);
            lt = expr(l, Type.isError(rt) ? hint : rt);
        } else {
            lt = expr(l, hint);
            rt = expr(r, Type.isError(lt) ? hint : lt);
        }
        return new Type[] {lt, rt};
    }

    private Type binary(Binary b, Type expected) {
        BinOp op = b.op;
        if (op.isLogic()) {
            Type lt = expr(b.left, Type.BOOL);
            Type rt = expr(b.right, Type.BOOL);
            for (Object[] side : new Object[][] {{b.left, lt}, {b.right, rt}}) {
                Type t = (Type) side[1];
                if (!Type.isBool(t) && !Type.isError(t)) {
                    diags.error(Code.E0211, ((Expr) side[0]).span, "`" + op.text + "` needs bool operands, found " + t,
                        Type.isInt(t) ? "for bitwise operations use & or |; for conditions compare explicitly (x != 0)" : null);
                }
            }
            return Type.BOOL;
        }
        if (op.isShift()) {
            Type lt = expr(b.left, Type.isInt(expected) ? expected : null);
            Type rt = expr(b.right, null);
            if (Type.isError(lt) || Type.isError(rt)) return Type.ERROR;
            if (!Type.isInt(lt) || !Type.isInt(rt)) {
                diags.error(Code.E0203, b.span, "`" + op.text + "` needs integer operands, found " + lt + " and " + rt);
                return Type.ERROR;
            }
            return lt;
        }
        Type hint = null;
        if (op.isArith() && Type.isNumeric(expected)) hint = expected;
        if (op.isBitwise() && Type.isInt(expected)) hint = expected;
        Type[] ts = sameTypeOperands(b.left, b.right, hint);
        Type lt = ts[0];
        Type rt = ts[1];
        if (Type.isError(lt) || Type.isError(rt)) return op.isArith() || op.isBitwise() ? Type.ERROR : Type.BOOL;
        if (op.isEquality()) {
            if (!Type.comparable(lt, rt)) {
                diags.error(Code.E0201, b.span, "cannot compare " + lt + " with " + rt,
                    Type.isNumeric(lt) && Type.isNumeric(rt) ? "convert one side: " + rt + "(...)" : null);
            }
            return Type.BOOL;
        }
        if (op.isOrdering()) {
            if (lt instanceof Type.Str && rt instanceof Type.Str) {
                // Byte by byte, a prefix first: the order of sort, of C's strcmp and of `<` on str in Python or Rust.
                return Type.BOOL;
            }
            if (!Type.isNumeric(lt) || !Type.isNumeric(rt)) {
                String orderHint = lt instanceof Type.Str || rt instanceof Type.Str
                    ? "a str is ordered only against another str; " + (Type.isInt(lt) || Type.isInt(rt)
                        ? "compare one byte, s[i], with a character such as 'a'" : "turn the other side into text first")
                    : "only numbers and strs are ordered; other values are compared with == and !=, so compare the numbers in them";
                diags.error(Code.E0203, b.span, "`" + op.text + "` needs two numbers or two strs, found " + lt + " and " + rt, orderHint);
            } else if (!lt.equals(rt)) {
                diags.error(Code.E0201, b.span, "cannot compare " + lt + " with " + rt,
                    "convert one side explicitly, e.g. " + lt + "(" + exprText(b.right) + ")");
            }
            return Type.BOOL;
        }
        boolean intOnly = op.isBitwise() || op == BinOp.MOD;
        if (!Type.isNumeric(lt) || !Type.isNumeric(rt) || intOnly && (!Type.isInt(lt) || !Type.isInt(rt))) {
            String need = intOnly ? "integers" : "numbers";
            String hint2 = null;
            if (op == BinOp.ADD && (lt instanceof Type.Str || rt instanceof Type.Str)) {
                hint2 = "strings are joined with append(mut s, a, b)";
            } else if (op == BinOp.MOD && Type.isFloat(lt)) {
                hint2 = "% is only defined for integers";
            }
            diags.error(Code.E0203, b.span, "`" + op.text + "` needs " + need + ", found " + lt + " and " + rt, hint2);
            return Type.ERROR;
        }
        if (!lt.equals(rt)) {
            // With a numeric target, convert the operands to it; "convert one side to the left's type" would
            // send `let avg: f64 = total / len(xs)` to an integer division and only then to f64. Narrowing a
            // float operand to an integer target would drop its fraction early, so that case keeps the old hint.
            String fix = Type.isNumeric(expected) && (Type.isFloat(expected) || Type.isInt(lt) && Type.isInt(rt))
                ? "convert the operands to " + expected + ": " + convertOperands(b, expected)
                : "convert one side explicitly, e.g. " + lt + "(" + exprText(b.right) + ")";
            diags.error(Code.E0201, b.span, "operands of `" + op.text + "` have different types: " + lt + " and " + rt, fix);
            return Type.ERROR;
        }
        return lt;
    }

    /** {@code b} with each operand not already of type {@code to} wrapped in a conversion to it. */
    private String convertOperands(Binary b, Type to) {
        return convertOperand(b.left, to) + " " + b.op.text + " " + convertOperand(b.right, to);
    }

    private String convertOperand(Expr e, Type to) {
        String text = exprText(e);
        if (e instanceof IntLit lit && !lit.isChar) return text; // a literal takes the type it is used as
        if (e instanceof Convert c && Type.isInt(e.type) && Type.isInt(c.arg.type) && !e.type.equals(to)) {
            return convertOperand(c.arg, to); // f64(len(xs)), not f64(i32(len(xs)))
        }
        if (!e.type.equals(to)) return to + "(" + text + ")";
        return e instanceof Binary ? "(" + text + ")" : text; // the span leaves out the source's parentheses
    }

    private Type arrayLit(ArrayLit a, Type expected) {
        if (unknownSize(a, expected, !a.elems.isEmpty())) {
            for (Expr e : a.elems) expr(e, Type.elementOf(expected));
            return Type.ERROR;
        }
        if (expected instanceof Type.ArrayT at) {
            if (a.elems.size() != at.len()) {
                diags.error(Code.E0201, a.span, "expected " + at.len() + " element(s) for " + at + ", found " + a.elems.size(),
                    a.elems.size() < at.len() ? "arrays always have exactly N elements; use list[T; N] for fewer" : null);
            }
            for (Expr e : a.elems) requireAssignable(e, expr(e, at.elem()), at.elem());
            return at;
        }
        if (expected instanceof Type.ListT lt) {
            if (lt.cap() >= 0 && a.elems.size() > lt.cap()) {
                diags.error(Code.E0201, a.span, "list literal has " + a.elems.size() + " elements but the capacity of "
                    + lt + " is " + lt.cap());
            }
            for (Expr e : a.elems) requireAssignable(e, expr(e, lt.elem()), lt.elem());
            return lt;
        }
        if (a.elems.isEmpty()) {
            if (expected != null && Type.isError(expected)) return Type.ERROR;
            diags.error(Code.E0216, a.span, "cannot infer the type of an empty list literal",
                "assign it to a typed variable: let xs: list[i32; 8] = [];");
            return Type.ERROR;
        }
        // The element type comes from the first element that is not a bare literal (or, among
        // literals only, from a float literal), so that [1, x] types like [x, 1] and [1, 2.5] like
        // [2.5, 1]. Strings and lists then widen to the largest capacity: ["a", "bbb"] is a [str[3]; 2].
        int n = a.elems.size();
        int anchor = 0;
        for (int i = 0; i < n; i++) {
            if (!isUntyped(a.elems.get(i))) {
                anchor = i;
                break;
            }
            if (hasFloatLiteral(a.elems.get(i)) && !hasFloatLiteral(a.elems.get(anchor))) anchor = i;
        }
        Type[] ts = new Type[n];
        ts[anchor] = expr(a.elems.get(anchor), null);
        Type t0 = ts[anchor];
        if (t0 instanceof Type.Void) {
            diags.error(Code.E0201, a.elems.get(anchor).span, "`" + exprText(a.elems.get(anchor)) + "` returns nothing, "
                + "so it cannot be an element of an array", "call it as a statement of its own");
            for (int i = 0; i < n; i++) {
                if (i != anchor) expr(a.elems.get(i), null);
            }
            return Type.ERROR;
        }
        Type elem = t0;
        for (int i = 0; i < n; i++) {
            if (i != anchor) ts[i] = expr(a.elems.get(i), Type.isError(t0) ? null : t0);
            elem = widen(elem, ts[i]);
        }
        for (int i = 0; i < n; i++) requireAssignable(a.elems.get(i), ts[i], elem);
        return Type.isError(elem) ? elem : new Type.ArrayT(elem, n);
    }

    /** The smaller of two str (or list) types widened to the larger capacity; otherwise {@code a}. */
    private static Type widen(Type a, Type b) {
        if (a instanceof Type.Str x && b instanceof Type.Str y) return y.cap() > x.cap() ? y : x;
        if (a instanceof Type.ListT x && b instanceof Type.ListT y && x.elem().equals(y.elem())) {
            return y.cap() > x.cap() ? y : x;
        }
        return a;
    }

    /**
     * Reports a literal that should fill a list or array whose capacity or length is a parameter's `_`, unknown
     * here; only an empty list literal is sure to fit. Returns whether it did.
     */
    private boolean unknownSize(Expr lit, Type expected, boolean elements) {
        if (expected instanceof Type.ListT lt && lt.cap() < 0 && elements) {
            diags.error(Code.E0201, lit.span, "a list literal may not fit " + expected + ": its capacity is not known here",
                "clear the list and push each element, which traps (R0004) when it is full: clear(mut xs); push(mut xs, v);");
            return true;
        }
        if (expected instanceof Type.ArrayT at && at.len() < 0) {
            diags.error(Code.E0201, lit.span, "an array literal may not have the length of " + expected
                    + ": it is not known here", "set the elements one at a time: for i in 0..len(xs) { xs[i] = v; }");
            return true;
        }
        return false;
    }

    private Type repeat(Repeat r, Type expected) {
        if (unknownSize(r, expected, true)) {
            constSize(r.count, "repeat count");
            expr(r.value, Type.elementOf(expected));
            return Type.ERROR;
        }
        int n = constSize(r.count, "repeat count");
        r.n = Math.max(n, 0);
        if (expected instanceof Type.ArrayT at) {
            if (n >= 0 && n != at.len()) {
                diags.error(Code.E0201, r.span, "expected " + at.len() + " elements for " + at + ", found [_; " + n + "]");
            }
            requireAssignable(r.value, expr(r.value, at.elem()), at.elem());
            return at;
        }
        if (expected instanceof Type.ListT lt) {
            if (n > lt.cap()) {
                diags.error(Code.E0201, r.span, "[_; " + n + "] exceeds the capacity of " + lt);
            }
            requireAssignable(r.value, expr(r.value, lt.elem()), lt.elem());
            return lt;
        }
        Type t = expr(r.value, null);
        if (t instanceof Type.Void) {
            diags.error(Code.E0201, r.value.span, "`" + exprText(r.value) + "` returns nothing, "
                + "so it cannot be an element of an array", "call it as a statement of its own");
            return Type.ERROR;
        }
        return n < 0 || Type.isError(t) ? Type.ERROR : new Type.ArrayT(t, n);
    }

    // =================================================================== helpers

    private void requireAssignable(Expr e, Type from, Type to) {
        if (Type.assignable(from, to)) return;
        mismatch(e, from, to);
    }

    private void mismatch(Expr e, Type from, Type to) {
        String hint = null;
        if (Type.isInt(from) && Type.isFloat(to) && e instanceof Binary b && b.op == BinOp.DIV) {
            hint = "convert the operands to divide as floats: " + convertOperands(b, to) + "; "
                + to + "(" + exprText(e) + ") would divide integers first and drop the fraction";
        } else if (Type.isNumeric(from) && Type.isNumeric(to)) {
            hint = "convert explicitly: " + to + "(" + exprText(e) + ")";
        } else if (Type.isNumeric(from) && Type.isBool(to)) {
            hint = "there is no truthiness; compare explicitly, e.g. " + exprText(e) + " != 0";
        } else if (Type.isBool(from) && Type.isInt(to)) {
            hint = "convert explicitly: " + to + "(" + exprText(e) + ")";
        } else if (from instanceof Type.Str && to instanceof Type.Str ts && ts.cap() < 0) {
            hint = "the capacity of " + to + " is not known here, so only an empty str is sure to fit; append the text to "
                + "an empty one instead, which traps (R0004) if it does not fit";
        } else if (from instanceof Type.ListT fl && to instanceof Type.ListT tl && tl.cap() < 0 && fl.elem().equals(tl.elem())) {
            hint = "the capacity of " + to + " is not known here, so only an empty list is sure to fit; push the elements "
                + "to an empty one instead, which traps (R0004) when it is full";
        } else if (from instanceof Type.Str && to instanceof Type.Str && e instanceof StrLit) {
            hint = "a " + from + " may not fit in " + to + "; increase the capacity of the target";
        } else if (from instanceof Type.Str && to instanceof Type.Str ts) {
            hint = "a " + from + " may not fit in " + to + "; convert it: " + to + "(" + exprText(e)
                + "), which traps (R0004) if it is longer than " + ts.cap() + " bytes"
                + (e.isPlace() ? " (check len(" + exprText(e) + ") first)" : "") + ", or increase the capacity of the target";
        } else if (from instanceof Type.ListT fl && to instanceof Type.ListT tl && fl.elem().equals(tl.elem())) {
            hint = "a " + from + " may not fit in " + to + "; convert it: " + to + "(" + exprText(e)
                + "), which traps (R0004) if it has more than " + tl.cap() + " elements, or increase the capacity of the target";
        } else if (from instanceof Type.ListT && to instanceof Type.ListT) {
            hint = "lists convert only to lists of the same element type";
        } else if (from instanceof Type.ArrayT && to instanceof Type.ListT) {
            hint = "arrays and lists are different types; only array literals like [1, 2] can initialize a list";
        }
        diags.error(Code.E0201, e.span, "type mismatch: expected " + to + ", found " + from, hint);
    }

    private String exprText(Expr e) {
        String t = (e.span.file() == null ? diags.source() : e.span.file()).text(e.span);
        return t.isEmpty() ? "x" : t;
    }

    /** Can this expression be evaluated at compile time? */
    static boolean isConst(Expr e) {
        return switch (e) {
            case IntLit i -> true;
            case FloatLit f -> true;
            case StrLit s -> true;
            case BoolLit b -> true;
            case Name n -> n.constant != null && n.constant.value != null;
            case FieldAccess f -> f.variant != null || isConst(f.target);
            case Index i -> isConst(i.target) && isConst(i.index);
            case Call c -> c.builtin != null && c.builtin.pure && c.args.stream().allMatch(a -> isConst(a.expr()));
            case VariantCall v -> v.args.stream().allMatch(a -> isConst(a.expr()));
            case StructLit s -> s.fields.stream().allMatch(f -> isConst(f.value()));
            case Convert c -> isConst(c.arg);
            case Resize r -> isConst(r.arg);
            case Unary u -> isConst(u.operand);
            case Binary b -> isConst(b.left) && isConst(b.right);
            case ArrayLit a -> a.elems.stream().allMatch(Checker::isConst);
            case Repeat r -> isConst(r.value);
        };
    }

    static String didYouMean(String name, Collection<String> candidates) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        // Sorted so that ties always resolve the same way, independent of map iteration order.
        for (String c : new java.util.TreeSet<>(candidates)) {
            int d = editDistance(name.toLowerCase(java.util.Locale.ROOT), c.toLowerCase(java.util.Locale.ROOT));
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        int limit = name.length() <= 3 ? 1 : name.length() <= 6 ? 2 : 3;
        if (best != null && bestD <= limit) {
            return "did you mean `" + best + "`?";
        }
        return null;
    }

    /** Optimal string alignment distance (Levenshtein plus adjacent transpositions). */
    private static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
