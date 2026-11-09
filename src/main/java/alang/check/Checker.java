package alang.check;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.diag.Code;
import alang.diag.Diagnostics;
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

    private final Program program;
    private final Diagnostics diags;

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
        boolean inEnsures;
        Expr mutCallAllowed;
    }

    private Ctx ctx = new Ctx();

    private Checker(Program program, Diagnostics diags) {
        this.program = program;
        this.diags = diags;
    }

    public static CheckResult check(Program program, Diagnostics diags, long maxStack) {
        Checker c = new Checker(program, diags);
        return c.run(maxStack);
    }

    private CheckResult run(long maxStack) {
        collect();
        for (StructDecl s : structs.values()) ensureStruct(s);
        for (EnumDecl e : enums.values()) ensureEnum(e);
        boolean recursiveTypes = checkRecursiveTypes();
        for (ConstDecl c : consts.values()) ensureConst(c);
        for (FnDecl f : fns.values()) signature(f);
        for (Item item : program.items) {
            if (item instanceof FnDecl f && fns.get(f.name) == f) body(f);
            if (item instanceof TestDecl t) testBody(t);
        }
        boolean recursion = checkRecursion();
        MemoryReport memory = null;
        if (!diags.hasErrors() && !recursiveTypes && !recursion) {
            memory = MemoryReport.compute(program);
            if (maxStack > 0) {
                for (MemoryReport.Entry e : memory.roots) {
                    if (e.stackBytes() > maxStack) {
                        diags.error(Code.E0402, e.routine().span,
                            e.label() + " needs up to " + e.stackBytes() + " bytes of stack, budget is " + maxStack,
                            "reduce array/list/str capacities, or pass large values as `mut` parameters");
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
                    if (typeExists(s.name)) duplicate(s.span, "type", s.name);
                    else structs.put(s.name, s);
                    Set<String> seen = new HashSet<>();
                    for (Field f : s.fields) {
                        if (!seen.add(f.name)) duplicate(f.span, "field", f.name);
                    }
                }
                case EnumDecl e -> {
                    if (typeExists(e.name)) duplicate(e.span, "type", e.name);
                    else enums.put(e.name, e);
                    Set<String> seen = new HashSet<>();
                    for (Variant v : e.variants) {
                        if (!seen.add(v.name)) duplicate(v.span, "variant", v.name);
                    }
                }
                case ConstDecl c -> {
                    if (consts.containsKey(c.name)) duplicate(c.span, "constant", c.name);
                    else consts.put(c.name, c);
                }
                case FnDecl f -> {
                    if (Builtin.byName(f.name) != null) {
                        diags.error(Code.E0108, f.nameSpan, "`" + f.name + "` is a builtin function and cannot be redefined",
                            "choose a different name, e.g. my_" + f.name);
                    } else if (fns.containsKey(f.name)) {
                        duplicate(f.nameSpan, "function", f.name);
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
        for (FnDecl f : fns.values()) {
            if (structs.containsKey(f.name)) {
                diags.error(Code.E0104, f.nameSpan, "function `" + f.name + "` has the same name as a struct",
                    "struct literals and calls share syntax; rename one of them");
            }
        }
    }

    private boolean typeExists(String name) {
        return structs.containsKey(name) || enums.containsKey(name);
    }

    private void duplicate(Span span, String what, String name) {
        diags.error(Code.E0104, span, "duplicate " + what + " `" + name + "`", "rename or remove one of the declarations");
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
        Type t = expr(c.init, c.type);
        requireAssignable(c.init, t, c.type);
        // An error-typed initializer (e.g. one using a constant that already failed) was reported
        // where it went wrong; skip evaluation without piling on another diagnostic.
        if (diags.errorCount() == before && !Type.isError(t)) {
            if (!isConst(c.init)) {
                diags.error(Code.E0209, c.init.span, "the value of constant `" + c.name + "` is not a constant expression",
                    "constants may use literals, other constants, operators and conversions, but not function calls");
            } else {
                c.value = evalConst(c.init);
            }
        }
        // A constant without a value is error-typed, so its uses (other constants, array sizes)
        // stay quiet instead of each reporting a follow-on error.
        if (c.value == null) c.type = Type.ERROR;
        ctx = saved;
        c.state = State.DONE;
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
            case PrimRef p -> Type.prim(p.kind);
            case StrRef s -> {
                int cap = constSize(s.cap, "string capacity");
                yield cap < 0 ? Type.ERROR : new Type.Str(cap);
            }
            case ListRef l -> {
                Type elem = resolveType(l.elem);
                int cap = constSize(l.cap, "list capacity");
                yield cap < 0 || Type.isError(elem) ? Type.ERROR : new Type.ListT(elem, cap);
            }
            case ArrayRef a -> {
                Type elem = resolveType(a.elem);
                int len = constSize(a.len, "array length");
                yield len < 0 || Type.isError(elem) ? Type.ERROR : new Type.ArrayT(elem, len);
            }
            case NamedRef n -> {
                if (structs.containsKey(n.name)) yield new Type.StructT(structs.get(n.name));
                if (enums.containsKey(n.name)) yield new Type.EnumT(enums.get(n.name));
                diags.error(Code.E0103, n.span, "unknown type `" + n.name + "`", typeHint(n.name));
                yield Type.ERROR;
            }
        };
    }

    private String typeHint(String name) {
        switch (name) {
            case "int", "long", "isize", "usize", "Int", "integer" -> {
                return "use a sized integer type such as i32 or i64 (len() returns i64)";
            }
            case "uint" -> {
                return "use u32 or u64";
            }
            case "float", "double", "number", "Float" -> {
                return "use f64 (or f32)";
            }
            case "string", "String", "text", "char_array" -> {
                return "strings have a fixed capacity: str[64]";
            }
            case "char", "byte" -> {
                return "use u8 for single bytes; character literals like 'a' are u8-compatible";
            }
            case "boolean", "Bool" -> {
                return "use bool";
            }
            case "void", "unit", "None" -> {
                return "omit the return type for functions that return nothing";
            }
            case "Vec", "List", "Array", "vector" -> {
                return "use list[T; N] (up to N items) or [T; N] (exactly N items)";
            }
            case "Option", "Optional", "Result" -> {
                return "there are no generics; declare an enum, e.g. enum MaybeI32 { None, Some(i32) }";
            }
            default -> {
                List<String> names = new ArrayList<>(structs.keySet());
                names.addAll(enums.keySet());
                for (PrimKind k : PrimKind.values()) names.add(k.name);
                return didYouMean(name, names);
            }
        }
    }

    /** Evaluates a constant size expression; returns -1 after reporting an error. */
    private int constSize(Expr e, String what) {
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
        Object v = evalConst(e);
        if (v == null) return -1;
        long n = (Long) v;
        if (Type.kind(t) == PrimKind.U64 && n < 0 || n < 1 || n > MAX_SIZE) {
            diags.error(Code.E0210, e.span, what + " must be between 1 and " + MAX_SIZE + ", found " + Long.toUnsignedString(n));
            return -1;
        }
        return (int) n;
    }

    private boolean checkRecursiveTypes() {
        boolean found = false;
        Set<Object> reported = new HashSet<>();
        for (StructDecl s : structs.values()) {
            if (contains(new Type.StructT(s), s, new HashSet<>()) && reported.add(s)) {
                diags.error(Code.E0403, s.span, "struct `" + s.name + "` contains itself and would have infinite size",
                    "store related items in a fixed-capacity list and refer to them by index");
                found = true;
            }
        }
        for (EnumDecl e : enums.values()) {
            if (contains(new Type.EnumT(e), e, new HashSet<>()) && reported.add(e)) {
                diags.error(Code.E0403, e.span, "enum `" + e.name + "` contains itself and would have infinite size",
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
        for (Param p : f.params) {
            Type t = resolveType(p.typeRef);
            if (!names.add(p.name)) {
                diags.error(Code.E0105, p.span, "duplicate parameter `" + p.name + "`");
            } else {
                checkNameFree(p.name, p.span);
            }
            if (p.name.equals("result") && !f.ensures.isEmpty()) {
                diags.error(Code.E0105, p.span, "parameter `result` conflicts with `result` in ensures",
                    "rename the parameter");
            }
            LocalVar v = new LocalVar(p.name, p.span, t, p.mut, p.mut, f.locals.size());
            f.locals.add(v);
            f.paramVars.add(v);
            if (f.tool && p.mut) {
                diags.error(Code.E0601, p.span, "tool functions cannot take `mut` parameters",
                    "tools receive JSON arguments; return the new value instead");
            }
        }
        if (f.name.equals("main") && (!f.params.isEmpty() || f.retRef != null)) {
            diags.error(Code.E0602, f.nameSpan, "`main` must take no parameters and return nothing",
                "declare it as fn main() { ... }; use tool functions for input and output");
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
                StringBuilder sb = new StringBuilder();
                for (FnDecl c : cycle) {
                    if (sb.length() > 0) sb.append(" -> ");
                    sb.append(c.name);
                }
                FnDecl head = cycle.get(0);
                diags.error(Code.E0401, head.nameSpan, "recursion is not allowed: " + sb,
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
        for (FnDecl c : f.callees) {
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
        boolean jumps = stmts(b.stmts);
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
        switch (s) {
            case Let l -> {
                Type t = resolveType(l.typeRef);
                ctx.mutCallAllowed = l.init;
                Type it = expr(l.init, t);
                ctx.mutCallAllowed = null;
                requireAssignable(l.init, it, t);
                l.var = declare(l.name, l.nameSpan, t, l.mutable);
                return false;
            }
            case Assign a -> {
                assign(a);
                return false;
            }
            case If i -> {
                boolean all = i.elseBlock != null;
                for (int k = 0; k < i.conds.size(); k++) {
                    condition(i.conds.get(k), "if");
                    if (!block(i.blocks.get(k))) all = false;
                }
                if (i.elseBlock != null && !block(i.elseBlock)) all = false;
                return all;
            }
            case While w -> {
                condition(w.cond, "while");
                Type lt = expr(w.limit, Type.I64);
                if (!Type.isInt(lt) && !Type.isError(lt)) {
                    diags.error(Code.E0201, w.limit.span, "loop limit must be an integer, found " + lt);
                }
                ctx.loopDepth++;
                block(w.body);
                ctx.loopDepth--;
                return false;
            }
            case For f -> {
                forLoop(f);
                return false;
            }
            case Match m -> {
                return match(m);
            }
            case Break b -> {
                if (ctx.loopDepth == 0) diags.error(Code.E0302, b.span, "`break` outside of a loop");
                return true;
            }
            case Continue c -> {
                if (ctx.loopDepth == 0) diags.error(Code.E0302, c.span, "`continue` outside of a loop");
                return true;
            }
            case Return r -> {
                returnStmt(r);
                return true;
            }
            case Assert a -> {
                condition(a.cond, "assert");
                return false;
            }
            case ExprStmt e -> {
                return exprStmt(e);
            }
        }
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
                immutableHint(v));
        }
        ctx.mutCallAllowed = a.value;
        Type vt = expr(a.value, tt);
        ctx.mutCallAllowed = null;
        if (a.op == null) {
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

    private String immutableHint(LocalVar v) {
        if (ctx.fn != null && ctx.fn.paramVars.contains(v)) {
            return "parameters are read-only; declare it as `" + v.name + ": mut " + v.type
                + "` (callers then pass `mut x`), or copy it: var " + v.name + "2: " + v.type + " = " + v.name + ";";
        }
        return "declare it with `var` instead of `let`";
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
        } else {
            Type ct = expr(f.from, null);
            varType = Type.elementOf(ct);
            if (varType == null) {
                if (!Type.isError(ct)) {
                    diags.error(Code.E0212, f.from.span, "cannot iterate over " + ct,
                        Type.isInt(ct) ? "to count, use a range: for i in 0..n { }" : "iterate over an array, list or str");
                }
                varType = Type.ERROR;
            }
        }
        ctx.scopes.push(new HashMap<>());
        f.var = declare(f.name, f.nameSpan, varType, false);
        ctx.loopDepth++;
        block(f.body);
        ctx.loopDepth--;
        ctx.scopes.pop();
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
        Type t = expr(r.value, ret);
        ctx.mutCallAllowed = null;
        requireAssignable(r.value, t, ret);
    }

    private boolean exprStmt(ExprStmt s) {
        Expr e = s.expr;
        if (e instanceof Call c) {
            ctx.mutCallAllowed = c;
            Type t = expr(c, null);
            ctx.mutCallAllowed = null;
            if (c.builtin == Builtin.PANIC) return true;
            if (!(t instanceof Type.Void) && !Type.isError(t) && c.builtin != Builtin.POP) {
                diags.warning(Code.W0302, c.span, "the result of `" + c.name + "` (" + t + ") is not used",
                    "store it: let value: " + t + " = " + c.name + "(...);");
            }
            return false;
        }
        expr(e, null);
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
            Pattern p = arm.pattern;
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
            if (!block(arm.body)) allJump = false;
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
            v.vars.add(b == null ? null : declare(b, v.bindingSpans.get(i), variant.payload.get(i), false));
        }
    }

    private static List<String> variantNames(EnumDecl d) {
        List<String> out = new ArrayList<>();
        for (Variant v : d.variants) out.add(v.name);
        return out;
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
        switch (e) {
            case IntLit i -> {
                if (expected != null && Type.isInt(expected)) {
                    if (!Type.kind(expected).fits(i.value)) {
                        diags.error(Code.E0202, i.span, "literal " + i.value + " does not fit in " + expected,
                            expected + " holds " + Type.kind(expected).min() + ".." + Type.kind(expected).max());
                    }
                    return expected;
                }
                if (expected != null && Type.isFloat(expected)) return expected;
                if (PrimKind.I64.fits(i.value)) return Type.I64;
                if (PrimKind.U64.fits(i.value)) return Type.U64;
                diags.error(Code.E0202, i.span, "literal " + i.value + " does not fit in any integer type");
                return Type.ERROR;
            }
            case FloatLit f -> {
                return expected != null && Type.kind(expected) == PrimKind.F32 ? Type.F32 : Type.F64;
            }
            case StrLit s -> {
                return new Type.Str(s.value.length);
            }
            case BoolLit b -> {
                return Type.BOOL;
            }
            case Name n -> {
                return name(n);
            }
            case FieldAccess f -> {
                return fieldAccess(f);
            }
            case Index ix -> {
                Type tt = expr(ix.target, null);
                Type it = expr(ix.index, Type.I64);
                if (!Type.isInt(it) && !Type.isError(it)) {
                    diags.error(Code.E0212, ix.index.span, "index must be an integer, found " + it);
                }
                if (Type.isError(tt)) return Type.ERROR;
                Type elem = Type.elementOf(tt);
                if (elem == null) {
                    diags.error(Code.E0212, ix.target.span, "cannot index a value of type " + tt);
                    return Type.ERROR;
                }
                return elem;
            }
            case Call c -> {
                return call(c);
            }
            case VariantCall v -> {
                return variantCall(v);
            }
            case StructLit s -> {
                return structLit(s);
            }
            case Convert c -> {
                return convert(c);
            }
            case Unary u -> {
                return unary(u, expected);
            }
            case Binary b -> {
                return binary(b, expected);
            }
            case ArrayLit a -> {
                return arrayLit(a, expected);
            }
            case Repeat r -> {
                return repeat(r, expected);
            }
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
                List<String> names = new ArrayList<>();
                for (Field fd : st.decl().fields) names.add(fd.name);
                diags.error(Code.E0106, f.nameSpan, "struct `" + st.decl().name + "` has no field `" + f.name + "`",
                    didYouMean(f.name, names));
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
        List<String> out = new ArrayList<>();
        for (Type t : ts) out.add(t.toString());
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ calls

    private Type call(Call c) {
        Builtin b = Builtin.byName(c.name);
        if (b != null) {
            c.builtin = b;
            Type t = builtin(c, b);
            checkMutCallPosition(c, t);
            checkAliasing(c.args);
            return t;
        }
        FnDecl f = fns.get(c.name);
        if (f == null) {
            for (Arg a : c.args) expr(a.expr, null);
            if (structs.containsKey(c.name)) {
                StructDecl s = structs.get(c.name);
                List<String> parts = new ArrayList<>();
                for (Field fd : s.fields) parts.add(fd.name + ": ...");
                diags.error(Code.E0213, c.span, "struct values need named fields",
                    "write " + s.name + "(" + String.join(", ", parts) + ")");
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
        if (ctx.routine != null) ctx.routine.callees.add(f);
        checkMutCallPosition(c, f.ret);
        if (c.args.size() != f.params.size()) {
            diags.error(Code.E0204, c.span, "`" + f.name + "` takes " + f.params.size() + " argument(s) but "
                + c.args.size() + " were given", "signature: " + signatureText(f));
            for (Arg a : c.args) expr(a.expr, null);
            return f.ret;
        }
        for (int i = 0; i < c.args.size(); i++) {
            Arg a = c.args.get(i);
            LocalVar p = f.paramVars.get(i);
            if (p.byRef) {
                if (!a.mut) {
                    expr(a.expr, p.type);
                    diags.error(Code.E0207, a.span, "parameter `" + p.name + "` of `" + f.name + "` is `mut`",
                        "pass a variable by reference: mut " + exprText(a.expr));
                    continue;
                }
                Type at = mutPlace(a.expr);
                if (!Type.isError(at) && !Type.isError(p.type) && !at.equals(p.type)) {
                    diags.error(Code.E0207, a.span, "`mut` argument must have exactly type " + p.type + ", found " + at,
                        "`mut` parameters cannot convert or widen; declare the variable as " + p.type);
                }
            } else {
                if (a.mut) {
                    diags.error(Code.E0207, a.span, "parameter `" + p.name + "` of `" + f.name + "` is not `mut`",
                        "remove `mut`; the value is passed read-only");
                }
                Type at = expr(a.expr, p.type);
                requireAssignable(a.expr, at, p.type);
            }
        }
        checkAliasing(c.args);
        return f.ret;
    }

    private String functionHint(String name) {
        return switch (name) {
            case "println", "printf", "printLn", "puts", "console_log", "echo" -> "use print(values...)";
            case "length", "size", "count", "strlen" -> "use len(x)";
            case "capacity" -> "use cap(x)";
            case "format", "sprintf", "concat", "str_cat", "strcat", "to_string", "str", "string" ->
                "build strings with append(mut s, values...) into a str[N] variable";
            case "add", "insert", "append_item", "push_back" -> "use push(mut xs, value)";
            case "exit", "abort", "fail", "error", "throw", "raise" -> "use panic(\"message\")";
            case "range" -> "use a range loop: for i in 0..n { }";
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
        Type t = expr(place, null);
        if (v == null) {
            if (consts.containsKey(root.name)) {
                diags.error(Code.E0205, root.span, "a constant cannot be passed as `mut`",
                    "copy it into a variable first: var x: T = " + root.name + ";");
            }
            return Type.ERROR;
        }
        if (!v.mutable) {
            diags.error(Code.E0205, root.span, "`" + v.name + "` is immutable and cannot be passed as `mut`",
                immutableHint(v));
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

    /** A variable passed as `mut` must not appear in any other argument of the same call. */
    private void checkAliasing(List<Arg> args) {
        for (int i = 0; i < args.size(); i++) {
            Arg a = args.get(i);
            if (!a.mut) continue;
            Name root = rootName(a.expr);
            if (root == null || root.local == null) continue;
            for (int j = 0; j < args.size(); j++) {
                if (j != i && mentions(args.get(j).expr, root.local)) {
                    diags.error(Code.E0208, args.get(j).span, "`" + root.name + "` is passed as `mut` and also used in "
                        + "another argument of the same call",
                        "copy the value into a separate variable first, then pass the copy");
                    return;
                }
            }
        }
    }

    private static boolean mentions(Expr e, LocalVar v) {
        return switch (e) {
            case Name n -> n.local == v;
            case FieldAccess f -> mentions(f.target, v);
            case Index i -> mentions(i.target, v) || mentions(i.index, v);
            case Call c -> c.args.stream().anyMatch(a -> mentions(a.expr, v));
            case VariantCall c -> c.args.stream().anyMatch(a -> mentions(a.expr, v));
            case StructLit s -> s.fields.stream().anyMatch(f -> mentions(f.value, v));
            case Convert c -> mentions(c.arg, v);
            case Unary u -> mentions(u.operand, v);
            case Binary b -> mentions(b.left, v) || mentions(b.right, v);
            case ArrayLit a -> a.elems.stream().anyMatch(x -> mentions(x, v));
            case Repeat r -> mentions(r.value, v);
            default -> false;
        };
    }

    private Type builtin(Call c, Builtin b) {
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
                Type t = expr(a.expr, null);
                boolean ok = b == Builtin.LEN ? Type.elementOf(t) != null : (t instanceof Type.ListT || t instanceof Type.Str);
                if (!ok && !Type.isError(t)) {
                    diags.error(Code.E0203, a.span, b.name + "() needs " + (b == Builtin.LEN ? "an array, list or str" : "a list or str")
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
                Type vt = expr(v.expr, elem);
                if (elem != null) requireAssignable(v.expr, vt, elem);
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
                if (!target.mut) {
                    expr(target.expr, null);
                    diags.error(Code.E0207, target.span, "append() modifies its first argument",
                        "write append(mut " + exprText(target.expr) + ", ...)");
                } else {
                    Type t = mutPlace(target.expr);
                    if (!(t instanceof Type.Str) && !Type.isError(t)) {
                        diags.error(Code.E0203, target.span, "append() needs a str[N] target, found " + t,
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
                Type t = expr(args.get(0).expr, null);
                if (Type.isError(t)) return t;
                if (!Type.isNumeric(t) || !Type.kind(t).signed) {
                    diags.error(Code.E0203, args.get(0).span, "abs() needs a signed integer or float, found " + t);
                    return Type.ERROR;
                }
                return t;
            }
            case MIN, MAX -> {
                if (!arity(c, 2)) return Type.ERROR;
                noMut(args.get(0));
                noMut(args.get(1));
                Type[] ts = sameTypeOperands(args.get(0).expr, args.get(1).expr, null);
                if (Type.isError(ts[0]) || Type.isError(ts[1])) return Type.ERROR;
                if (!Type.isNumeric(ts[0]) || !ts[0].equals(ts[1])) {
                    diags.error(Code.E0201, c.span, b.name + "() needs two numbers of the same type, found "
                        + ts[0] + " and " + ts[1]);
                    return Type.ERROR;
                }
                return ts[0];
            }
            case SQRT, FLOOR, CEIL -> {
                if (!arity(c, 1)) return Type.ERROR;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr, Type.F64);
                if (Type.isError(t)) return t;
                if (!Type.isFloat(t)) {
                    diags.error(Code.E0201, args.get(0).span, b.name + "() needs f32 or f64, found " + t,
                        "convert first: " + b.name + "(f64(x))");
                    return Type.ERROR;
                }
                return t;
            }
            case PANIC -> {
                if (!arity(c, 1)) return Type.VOID;
                noMut(args.get(0));
                Type t = expr(args.get(0).expr, null);
                if (!(t instanceof Type.Str) && !Type.isError(t)) {
                    diags.error(Code.E0201, args.get(0).span, "panic() needs a string message, found " + t);
                }
                return Type.VOID;
            }
        }
        throw new IllegalStateException();
    }

    private void printable(Arg a) {
        noMut(a);
        if (a.expr instanceof IntLit lit && lit.isChar) {
            diags.warning(Code.W0303, a.span, "character literal " + exprText(a.expr) + " is the number " + lit.value
                + " and prints as digits", "to add the character to a str use push(mut s, " + exprText(a.expr) + ")");
        }
        Type t = expr(a.expr, null);
        if (t instanceof Type.Void) {
            diags.error(Code.E0201, a.span, "cannot print a call that returns nothing");
        }
    }

    private boolean arity(Call c, int n) {
        if (c.args.size() == n) return true;
        Builtin b = c.builtin;
        diags.error(Code.E0204, c.span, b.name + "() takes " + n + " argument(s) but " + c.args.size() + " were given",
            "signature: " + b.signature);
        for (Arg a : c.args) expr(a.expr, null);
        return false;
    }

    private void noMut(Arg a) {
        if (a.mut) diags.error(Code.E0207, a.span, "this argument is not modified; remove `mut`");
    }

    /** First argument of push/pop/clear: `mut` list or str. */
    private Type collectionArg(Call c, Arg a) {
        if (!a.mut) {
            Type t = expr(a.expr, null);
            diags.error(Code.E0207, a.span, c.name + "() modifies its first argument",
                "write " + c.name + "(mut " + exprText(a.expr) + (c.builtin == Builtin.PUSH ? ", value)" : ")"));
            return t instanceof Type.ListT || t instanceof Type.Str ? t : Type.ERROR;
        }
        Type t = mutPlace(a.expr);
        if (!(t instanceof Type.ListT) && !(t instanceof Type.Str) && !Type.isError(t)) {
            diags.error(Code.E0203, a.span, c.name + "() needs a list or str, found " + t,
                t instanceof Type.ArrayT ? "arrays have a fixed length; use list[T; N] for a growable collection" : null);
            return Type.ERROR;
        }
        return t;
    }

    private Type variantCall(VariantCall v) {
        EnumDecl d = enums.get(v.enumName);
        if (d == null) {
            for (Arg a : v.args) expr(a.expr, null);
            if (lookupLocal(v.enumName) != null || consts.containsKey(v.enumName)) {
                String args = v.args.isEmpty() ? "" : ", ...";
                diags.error(Code.E0102, v.span, "there are no methods: `" + v.enumName + "." + v.variantName + "(...)` is not valid",
                    "pass the value as the first argument: " + v.variantName + "(" + v.enumName + args + ")");
            } else {
                diags.error(Code.E0103, v.span, "unknown enum `" + v.enumName + "`",
                    didYouMean(v.enumName, new ArrayList<>(enums.keySet())));
            }
            return Type.ERROR;
        }
        v.enumDecl = d;
        Variant variant = d.variant(v.variantName);
        if (variant == null) {
            for (Arg a : v.args) expr(a.expr, null);
            diags.error(Code.E0107, v.span, "enum `" + d.name + "` has no variant `" + v.variantName + "`",
                didYouMean(v.variantName, variantNames(d)));
            return Type.ERROR;
        }
        v.variant = variant;
        if (v.args.size() != variant.payload.size()) {
            for (Arg a : v.args) expr(a.expr, null);
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
            Type at = expr(a.expr, pt);
            requireAssignable(a.expr, at, pt);
        }
        return new Type.EnumT(d);
    }

    private Type structLit(StructLit s) {
        StructDecl d = structs.get(s.name);
        if (d == null) {
            for (FieldInit f : s.fields) expr(f.value, null);
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
            int idx = d.fieldIndex(fi.name);
            if (idx < 0) {
                List<String> names = new ArrayList<>();
                for (Field f : d.fields) names.add(f.name);
                diags.error(Code.E0106, fi.span, "struct `" + d.name + "` has no field `" + fi.name + "`",
                    didYouMean(fi.name, names));
                expr(fi.value, null);
                continue;
            }
            if (order[idx] >= 0) {
                diags.error(Code.E0213, fi.span, "field `" + fi.name + "` is given twice");
            }
            order[idx] = i;
            Type ft = d.fields.get(idx).type;
            Type vt = expr(fi.value, ft);
            requireAssignable(fi.value, vt, ft);
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
        if (!(t instanceof Type.Prim)) {
            diags.error(Code.E0214, c.span, "cannot convert " + t + " to " + target,
                "conversions work between numbers (and from bool to integers)");
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

    /** Checks two operands that should share a type, typing literals from the other side. */
    private Type[] sameTypeOperands(Expr l, Expr r, Type hint) {
        Type lt;
        Type rt;
        if (isUntyped(l) && !isUntyped(r)) {
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
            if (!Type.isNumeric(lt) || !Type.isNumeric(rt)) {
                diags.error(Code.E0203, b.span, "`" + op.text + "` needs numbers, found " + lt + " and " + rt);
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
            diags.error(Code.E0201, b.span, "operands of `" + op.text + "` have different types: " + lt + " and " + rt,
                "convert one side explicitly, e.g. " + lt + "(" + exprText(b.right) + ")");
            return Type.ERROR;
        }
        return lt;
    }

    private Type arrayLit(ArrayLit a, Type expected) {
        if (expected instanceof Type.ArrayT at) {
            if (a.elems.size() != at.len()) {
                diags.error(Code.E0201, a.span, "expected " + at.len() + " element(s) for " + at + ", found " + a.elems.size(),
                    a.elems.size() < at.len() ? "arrays always have exactly N elements; use list[T; N] for fewer" : null);
            }
            for (Expr e : a.elems) requireAssignable(e, expr(e, at.elem()), at.elem());
            return at;
        }
        if (expected instanceof Type.ListT lt) {
            if (a.elems.size() > lt.cap()) {
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
        Type t0 = expr(a.elems.get(0), null);
        for (int i = 1; i < a.elems.size(); i++) {
            Expr e = a.elems.get(i);
            requireAssignable(e, expr(e, t0), t0);
        }
        return Type.isError(t0) ? t0 : new Type.ArrayT(t0, a.elems.size());
    }

    private Type repeat(Repeat r, Type expected) {
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
        return n < 0 || Type.isError(t) ? Type.ERROR : new Type.ArrayT(t, n);
    }

    // =================================================================== helpers

    private void requireAssignable(Expr e, Type from, Type to) {
        if (Type.assignable(from, to)) return;
        mismatch(e, from, to);
    }

    private void mismatch(Expr e, Type from, Type to) {
        String hint = null;
        if (Type.isNumeric(from) && Type.isNumeric(to)) {
            hint = "convert explicitly: " + to + "(" + exprText(e) + ")";
        } else if (Type.isNumeric(from) && Type.isBool(to)) {
            hint = "there is no truthiness; compare explicitly, e.g. " + exprText(e) + " != 0";
        } else if (Type.isBool(from) && Type.isInt(to)) {
            hint = "convert explicitly: " + to + "(" + exprText(e) + ")";
        } else if (from instanceof Type.Str fs && to instanceof Type.Str ts) {
            hint = "a str[" + fs.cap() + "] may not fit in str[" + ts.cap() + "]; increase the capacity of the target";
        } else if (from instanceof Type.ListT && to instanceof Type.ListT) {
            hint = "lists convert only to lists of the same element type with equal or larger capacity";
        } else if (from instanceof Type.ArrayT && to instanceof Type.ListT) {
            hint = "arrays and lists are different types; only array literals like [1, 2] can initialize a list";
        }
        diags.error(Code.E0201, e.span, "type mismatch: expected " + to + ", found " + from, hint);
    }

    private String exprText(Expr e) {
        String line = diags.source().line(e.span.line());
        int start = Math.max(0, e.span.col() - 1);
        int end = Math.min(line.length(), start + e.span.length());
        return start < end ? line.substring(start, end) : "x";
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
            case Call c -> c.builtin != null && switch (c.builtin) {
                case LEN, CAP, ABS, MIN, MAX, SQRT, FLOOR, CEIL -> c.args.stream().allMatch(a -> isConst(a.expr));
                default -> false;
            };
            case VariantCall v -> v.args.stream().allMatch(a -> isConst(a.expr));
            case StructLit s -> s.fields.stream().allMatch(f -> isConst(f.value));
            case Convert c -> isConst(c.arg);
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
