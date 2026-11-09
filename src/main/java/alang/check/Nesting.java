package alang.check;

import alang.ast.Ast.*;
import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.diag.Span;
import alang.types.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fixed nesting limits (E0404). The checker, interpreter and C backend recurse over expressions,
 * statements, calls and types, so a deep enough program would exhaust the Java stack, at a depth
 * that differs between machines. These limits are the same everywhere; everything they accept runs
 * on a quarter of the default 1 MB thread stack (NestingTest). Every check here is iterative, so it
 * cannot overflow itself, and runs before the code that recurses over what it checks.
 */
public final class Nesting {
    private Nesting() {}

    /**
     * The deepest evaluation accepted: blocks, statements, expressions and types written in the
     * source each add a level, and a call made at depth d into a function whose body reaches depth n
     * reaches depth d + n.
     */
    public static final int MAX_DEPTH = 128;

    /** The deepest type accepted: a field, element or payload is one level deeper than its container. */
    public static final int MAX_TYPE_DEPTH = 128;

    // =================================================================== syntax

    /**
     * Reports the first node of each declaration that is nested more than {@link #MAX_DEPTH} levels
     * deep. Returns true if it reported any: the checker must then not recurse over the program.
     */
    static boolean checkSyntax(Program program, Diagnostics diags) {
        boolean found = false;
        for (Item item : program.items) {
            Deque<Object> nodes = new ArrayDeque<>();
            Deque<Integer> depths = new ArrayDeque<>();
            push(item, 0, nodes, depths);
            while (!nodes.isEmpty()) {
                Object node = nodes.pop();
                int depth = depths.pop();
                if (depth > MAX_DEPTH) {
                    diags.error(Code.E0404, span(node), "this " + kind(node) + " is nested " + depth + " levels deep in "
                        + label(item) + "; the limit is " + MAX_DEPTH,
                        "move the inner part into a `let` binding or a helper function");
                    found = true;
                    break;
                }
                push(node, depth, nodes, depths);
            }
        }
        return found;
    }

    /** Pushes the children of {@code node}, which is at {@code depth}, so they pop in source order. */
    private static void push(Object node, int depth, Deque<Object> nodes, Deque<Integer> depths) {
        List<Object> kids = children(node);
        for (int i = kids.size() - 1; i >= 0; i--) {
            nodes.push(kids.get(i));
            depths.push(depth + 1);
        }
    }

    // =================================================================== calls

    /**
     * Reports calls that reach more than {@link #MAX_DEPTH} levels, counting the depth of the call
     * and the depth the callee reaches, through its own calls. The call graph must be acyclic
     * (recursion already rejected) and every routine within {@link #MAX_DEPTH} on its own.
     */
    static void checkCalls(Program program, Diagnostics diags) {
        List<Routine> routines = program.itemsOf(Routine.class);
        Map<Routine, Integer> own = new HashMap<>();
        Map<Routine, List<Site>> sites = new HashMap<>();
        for (Routine r : routines) {
            List<Site> list = new ArrayList<>();
            own.put(r, scan(r, list));
            sites.put(r, list);
        }
        Map<Routine, Integer> reach = new HashMap<>();
        for (Routine r : routines) reach(r, own, sites, reach);
        for (Routine r : routines) {
            if (reach.get(r) <= MAX_DEPTH) continue;
            for (Site s : sites.get(r)) {
                int callee = reach.get(s.call.fn);
                if (callee > MAX_DEPTH || s.depth + callee <= MAX_DEPTH) continue;
                // The callee is within the limit on its own, so this call is where the limit is crossed.
                diags.error(Code.E0404, s.call.span, "calling `" + s.call.name + "` here reaches depth "
                    + (s.depth + callee) + ": the call is at depth " + s.depth + " of " + label(r) + ", and `"
                    + s.call.name + "` reaches depth " + callee + " through its own calls; the limit is " + MAX_DEPTH,
                    "call `" + s.call.name + "` from a shallower place, for example store its result with `let` "
                        + "first, or make it or the functions it calls nest less");
                break;
            }
        }
    }

    /** A call to a user function, at {@code depth} within its routine. */
    private record Site(Call call, int depth) {}

    /** Returns the deepest level within {@code r} and collects its calls to user functions. */
    private static int scan(Routine r, List<Site> sites) {
        int max = 0;
        Deque<Object> nodes = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        push(r, 0, nodes, depths);
        while (!nodes.isEmpty()) {
            Object node = nodes.pop();
            int depth = depths.pop();
            max = Math.max(max, depth);
            if (node instanceof Call c && c.fn != null) sites.add(new Site(c, depth));
            push(node, depth, nodes, depths);
        }
        return max;
    }

    /** Computes, without recursion, the depth each routine reaches through its calls. */
    private static void reach(Routine root, Map<Routine, Integer> own, Map<Routine, List<Site>> sites,
                              Map<Routine, Integer> reach) {
        Deque<Routine> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Routine r = stack.peek();
            if (reach.containsKey(r)) {
                stack.pop();
                continue;
            }
            boolean ready = true;
            for (Site s : sites.get(r)) {
                if (!reach.containsKey(s.call.fn)) {
                    stack.push(s.call.fn);
                    ready = false;
                }
            }
            if (!ready) continue;
            int depth = own.get(r);
            for (Site s : sites.get(r)) depth = Math.max(depth, s.depth + reach.get(s.call.fn));
            reach.put(r, depth);
            stack.pop();
        }
    }

    // =================================================================== types

    /**
     * Whether a declaration nests more than {@link #MAX_TYPE_DEPTH} levels (reported), and one
     * declaration on a cycle of declarations, or null.
     */
    record Types(boolean tooDeep, Item onCycle) {}

    /**
     * Reports struct and enum declarations whose types nest more than {@link #MAX_TYPE_DEPTH}
     * levels. When it reports any, the checker must not recurse through the declarations' types.
     */
    static Types checkTypes(Collection<StructDecl> structs, Collection<EnumDecl> enums, Diagnostics diags) {
        List<Item> decls = new ArrayList<>(structs);
        decls.addAll(enums);
        Map<Item, Integer> depth = new HashMap<>();
        Map<Item, Boolean> onPath = new HashMap<>();
        Item onCycle = null;
        for (Item root : decls) {
            if (depth.containsKey(root)) continue;
            // Depth-first, without recursion: a path of declarations, each with its unvisited components.
            Deque<Item> path = new ArrayDeque<>();
            Deque<Iterator<Item>> next = new ArrayDeque<>();
            path.push(root);
            next.push(references(root).iterator());
            onPath.put(root, true);
            while (!path.isEmpty()) {
                Iterator<Item> it = next.peek();
                if (it.hasNext()) {
                    Item d = it.next();
                    if (onPath.getOrDefault(d, false)) {
                        onCycle = d;
                    } else if (!depth.containsKey(d)) {
                        path.push(d);
                        next.push(references(d).iterator());
                        onPath.put(d, true);
                    }
                    continue;
                }
                Item d = path.pop();
                next.pop();
                onPath.put(d, false);
                int max = 0;
                for (Type t : components(d)) max = Math.max(max, typeDepth(t, depth));
                depth.put(d, 1 + max);
            }
        }
        boolean tooDeep = false;
        for (Item d : decls) {
            if (depth.get(d) <= MAX_TYPE_DEPTH) continue;
            tooDeep = true;
            boolean partsWithin = true;
            for (Item r : references(d)) partsWithin &= depth.get(r) <= MAX_TYPE_DEPTH;
            if (!partsWithin) continue;  // reported where the limit is crossed
            diags.error(Code.E0404, d.span, (d instanceof StructDecl ? "struct `" : "enum `") + d.name + "` nests "
                + depth.get(d) + " levels deep; the limit is " + MAX_TYPE_DEPTH,
                "flatten it: keep the inner values in a separate fixed-capacity list and refer to them by index");
        }
        return new Types(tooDeep, onCycle);
    }

    /** The field or payload types of a struct or enum declaration. */
    private static List<Type> components(Item decl) {
        List<Type> out = new ArrayList<>();
        if (decl instanceof StructDecl s) {
            for (Field f : s.fields) out.add(f.type);
        } else if (decl instanceof EnumDecl e) {
            for (Variant v : e.variants) out.addAll(v.payload);
        }
        return out;
    }

    /** The struct and enum declarations a declaration's components refer to, through arrays and lists. */
    private static List<Item> references(Item decl) {
        List<Item> out = new ArrayList<>();
        for (Type t : components(decl)) {
            Type inner = t;
            while (true) {
                if (inner instanceof Type.ArrayT a) {
                    inner = a.elem();
                } else if (inner instanceof Type.ListT l) {
                    inner = l.elem();
                } else {
                    break;
                }
            }
            if (inner instanceof Type.StructT s) out.add(s.decl());
            if (inner instanceof Type.EnumT e) out.add(e.decl());
        }
        return out;
    }

    /** Depth of a component type; the declarations it refers to are done or on the current path. */
    private static int typeDepth(Type t, Map<Item, Integer> depth) {
        int wrappers = 0;
        Type inner = t;
        while (true) {
            if (inner instanceof Type.ArrayT a) {
                inner = a.elem();
            } else if (inner instanceof Type.ListT l) {
                inner = l.elem();
            } else {
                break;
            }
            wrappers++;
        }
        int base = 1;
        if (inner instanceof Type.StructT s) base = depth.getOrDefault(s.decl(), 1);
        if (inner instanceof Type.EnumT e) base = depth.getOrDefault(e.decl(), 1);
        return wrappers + base;
    }

    // =================================================================== the tree

    /** The direct children of an AST node, in source order. */
    private static List<Object> children(Object node) {
        List<Object> out = new ArrayList<>();
        switch (node) {
            case Item item -> item(item, out);
            case Block b -> out.addAll(b.stmts);
            case Stmt s -> stmt(s, out);
            case Expr e -> expr(e, out);
            case TypeRef t -> typeRef(t, out);
            default -> throw new IllegalStateException("not an AST node: " + node.getClass().getSimpleName());
        }
        out.removeIf(x -> x == null);
        return out;
    }

    private static void item(Item item, List<Object> out) {
        switch (item) {
            case ConstDecl c -> {
                out.add(c.typeRef);
                out.add(c.init);
            }
            case StructDecl s -> {
                for (Field f : s.fields) out.add(f.typeRef);
            }
            case EnumDecl e -> {
                for (Variant v : e.variants) out.addAll(v.payloadRefs);
            }
            case FnDecl f -> {
                for (Param p : f.params) out.add(p.typeRef);
                out.add(f.retRef);
                out.addAll(f.requires);
                out.addAll(f.ensures);
                out.add(f.body);
            }
            case TestDecl t -> out.add(t.body);
        }
    }

    private static void stmt(Stmt s, List<Object> out) {
        switch (s) {
            case Let l -> {
                out.add(l.typeRef);
                out.add(l.init);
            }
            case Assign a -> {
                out.add(a.target);
                out.add(a.value);
            }
            case If i -> {
                for (int k = 0; k < i.conds.size(); k++) {
                    out.add(i.conds.get(k));
                    out.add(i.blocks.get(k));
                }
                out.add(i.elseBlock);
            }
            case While w -> {
                out.add(w.cond);
                out.add(w.limit);
                out.add(w.body);
            }
            case For f -> {
                out.add(f.from);
                out.add(f.to);
                out.add(f.body);
            }
            case Match m -> {
                out.add(m.subject);
                for (Arm a : m.arms) out.add(a.body);
            }
            case Return r -> out.add(r.value);
            case Assert a -> out.add(a.cond);
            case ExprStmt e -> out.add(e.expr);
            case Break b -> {
            }
            case Continue c -> {
            }
        }
    }

    private static void expr(Expr e, List<Object> out) {
        switch (e) {
            case Index i -> {
                out.add(i.target);
                out.add(i.index);
            }
            case FieldAccess f -> out.add(f.target);
            case Call c -> {
                for (Arg a : c.args) out.add(a.expr);
            }
            case VariantCall v -> {
                for (Arg a : v.args) out.add(a.expr);
            }
            case StructLit s -> {
                for (FieldInit f : s.fields) out.add(f.value);
            }
            case Convert c -> out.add(c.arg);
            case Unary u -> out.add(u.operand);
            case Binary b -> {
                out.add(b.left);
                out.add(b.right);
            }
            case ArrayLit a -> out.addAll(a.elems);
            case Repeat r -> {
                out.add(r.value);
                out.add(r.count);
            }
            case IntLit i -> {
            }
            case FloatLit f -> {
            }
            case StrLit s -> {
            }
            case BoolLit b -> {
            }
            case Name n -> {
            }
        }
    }

    private static void typeRef(TypeRef t, List<Object> out) {
        switch (t) {
            case StrRef s -> out.add(s.cap);
            case ListRef l -> {
                out.add(l.elem);
                out.add(l.cap);
            }
            case ArrayRef a -> {
                out.add(a.elem);
                out.add(a.len);
            }
            case PrimRef p -> {
            }
            case NamedRef n -> {
            }
        }
    }

    private static Span span(Object node) {
        return switch (node) {
            case Block b -> b.span;
            case Stmt s -> s.span;
            case Expr e -> e.span;
            case TypeRef t -> t.span;
            case Item i -> i.span;
            default -> throw new IllegalStateException("not an AST node: " + node.getClass().getSimpleName());
        };
    }

    private static String kind(Object node) {
        return switch (node) {
            case Block b -> "block";
            case Stmt s -> "statement";
            case Expr e -> "expression";
            case TypeRef t -> "type";
            default -> "declaration";
        };
    }

    private static String label(Item item) {
        return switch (item) {
            case FnDecl f -> "fn " + f.name;
            case TestDecl t -> "test \"" + t.name + "\"";
            case ConstDecl c -> "const " + c.name;
            case StructDecl s -> "struct " + s.name;
            case EnumDecl e -> "enum " + e.name;
        };
    }
}
