package alang.check;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.types.Type;

/**
 * An upper bound on the aggregate temporaries a routine builds, which {@link MemoryReport} adds to its
 * frame. Without them the bound missed memory a program really uses:
 * {@code print([[0; 1048576]; 1048576][0][0])} declares no variable at all, but builds 8 TiB.
 *
 * <p>Every aggregate value an expression builds counts: literals, call results, copies of variables that
 * are stored somewhere, and widened copies (a {@code str[8]} stored as a {@code str[16]}); so does the
 * snapshot of a {@code for x in xs} loop. The temporaries of all statements are added up, not just those
 * of the largest one: the C backend gives each temporary its own variable, and a C compiler need not
 * reuse a variable's stack slot for another once it is dead (clang -O0 never does), so they can all be
 * on the stack at once. Scalars are not counted, and a value a statement then stores counts both as the
 * temporary and as the variable, so the figure errs on the high side.
 */
final class Temporaries {
    private Temporaries() {}

    /** The routine's body, preconditions and postconditions. */
    static long of(Routine r) {
        long sum = block(r.body, r);
        if (r instanceof FnDecl f) {
            for (Expr e : f.requires) sum = Layout.add(sum, expr(e));
            for (Expr e : f.ensures) sum = Layout.add(sum, expr(e));
        }
        return sum;
    }

    private static long block(Block b, Routine r) {
        long sum = 0;
        for (Stmt s : b.stmts) sum = Layout.add(sum, stmt(s, r));
        return sum;
    }

    private static long stmt(Stmt s, Routine r) {
        return switch (s) {
            case Let l -> Layout.add(expr(l.init), stored(l.init, l.var.type));
            case Assign a -> {
                long t = Layout.add(expr(a.target), expr(a.value));
                yield a.op == null ? Layout.add(t, stored(a.value, a.target.type)) : t;
            }
            case If i -> {
                long sum = i.elseBlock != null ? block(i.elseBlock, r) : 0;
                for (Expr c : i.conds) sum = Layout.add(sum, expr(c));
                for (Block b : i.blocks) sum = Layout.add(sum, block(b, r));
                yield sum;
            }
            case While w -> Layout.add(Layout.add(expr(w.cond), expr(w.limit)), block(w.body, r));
            case For f -> {
                if (f.isRange()) yield Layout.add(Layout.add(expr(f.from), expr(f.to)), block(f.body, r));
                // The snapshot of the collection lives as long as the loop: the value itself when the
                // collection is built by an expression (counted there), a copy when it is a variable.
                long snapshot = Layout.add(expr(f.from), isPlace(f.from) ? Layout.size(f.from.type) : 0);
                yield Layout.add(snapshot, block(f.body, r));
            }
            case Match m -> {
                long arms = 0;
                for (Arm a : m.arms) arms = Layout.add(arms, block(a.body, r));
                yield Layout.add(expr(m.subject), arms);
            }
            case Return ret -> {
                if (ret.value == null) yield 0;
                Type to = r instanceof FnDecl f ? f.ret : ret.value.type;
                yield Layout.add(expr(ret.value), stored(ret.value, to));
            }
            case Assert a -> expr(a.cond);
            case ExprStmt e -> expr(e.expr);
            case Break b -> 0;
            case Continue c -> 0;
        };
    }

    /** The aggregates that evaluating {@code e} builds, all of which may be alive at once. */
    static long expr(Expr e) {
        long own = Type.isAggregate(e.type) && !isPlace(e) ? Layout.size(e.type) : 0;
        long inner = switch (e) {
            case IntLit i -> 0;
            case FloatLit f -> 0;
            case StrLit s -> 0;
            case BoolLit b -> 0;
            case Name n -> 0;
            case FieldAccess f -> f.variant != null ? 0 : expr(f.target);
            case Index i -> Layout.add(expr(i.target), expr(i.index));
            case Call c -> call(c);
            case VariantCall v -> {
                long t = 0;
                for (int i = 0; i < v.args.size(); i++) {
                    Expr a = v.args.get(i).expr;
                    t = Layout.add(t, Layout.add(expr(a), stored(a, v.variant.payload.get(i))));
                }
                yield t;
            }
            case StructLit s -> {
                long t = 0;
                for (FieldInit f : s.fields) {
                    Type to = s.decl.fields.get(s.decl.fieldIndex(f.name)).type;
                    t = Layout.add(t, Layout.add(expr(f.value), stored(f.value, to)));
                }
                yield t;
            }
            case Convert c -> expr(c.arg);
            case Unary u -> expr(u.operand);
            case Binary b -> Layout.add(expr(b.left), expr(b.right));
            case ArrayLit a -> {
                Type elem = Type.elementOf(a.type);
                long t = 0;
                for (Expr x : a.elems) t = Layout.add(t, Layout.add(expr(x), stored(x, elem)));
                yield t;
            }
            case Repeat r -> Layout.add(expr(r.value), stored(r.value, Type.elementOf(r.type)));
        };
        return Layout.add(own, inner);
    }

    private static long call(Call c) {
        long t = 0;
        for (Arg a : c.args) t = Layout.add(t, expr(a.expr));
        if (c.fn != null) {
            // A read-only argument is passed as it is, unless it has to be widened to the parameter's type.
            for (int i = 0; i < c.args.size(); i++) {
                LocalVar p = c.fn.paramVars.get(i);
                Type given = c.args.get(i).expr.type;
                if (!p.byRef && Type.isAggregate(p.type) && !given.equals(p.type)) t = Layout.add(t, Layout.size(p.type));
            }
        } else if (c.builtin == Builtin.PUSH) {
            Expr v = c.args.get(1).expr;
            t = Layout.add(t, stored(v, Type.elementOf(c.args.get(0).expr.type)));
        }
        return t;
    }

    /**
     * What storing {@code e} as a {@code to} adds to the temporaries counted for {@code e} itself: a copy
     * when {@code e} is a variable, field or element (two variables never share storage), and the widened
     * copy when the types differ.
     */
    private static long stored(Expr e, Type to) {
        if (!Type.isAggregate(e.type)) return 0;
        long t = isPlace(e) ? Layout.size(e.type) : 0;
        return e.type.equals(to) ? t : Layout.add(t, Layout.size(to));
    }

    /** Places evaluate to the storage they name; everything else builds a new value. */
    private static boolean isPlace(Expr e) {
        return e instanceof Name || e instanceof Index || (e instanceof FieldAccess f && f.variant == null);
    }
}
