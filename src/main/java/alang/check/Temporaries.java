package alang.check;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.types.Type;
import java.util.List;

/**
 * An upper bound on the aggregate temporaries a routine builds, which {@link MemoryReport} adds to its
 * frame. Without them the bound missed memory a program really uses:
 * {@code print([[0; 1048576]; 1048576][0][0])} declares no variable at all, but builds 8 TiB.
 *
 * <p>Every aggregate value an expression builds counts: literals, call results, copies of variables that
 * are stored somewhere, and widened copies (a {@code str[8]} stored as a {@code str[16]}); so does the
 * snapshot a {@code for x in xs} loop takes of a variable its body may change. Two values are built where
 * they are kept, and so are counted once: what split, words, join and replace build for the variable a
 * let/var declares, and the pieces of {@code for x in split(s, sep)}, made one at a time in the loop
 * variable. The temporaries of all statements are added up, not just those
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
        for (Stmt s : b.stmts()) sum = Layout.add(sum, stmt(s, r));
        return sum;
    }

    private static long stmt(Stmt s, Routine r) {
        return switch (s) {
            case Let l -> l.builtInPlace() ? inner(l.init) : Layout.add(expr(l.init), stored(l.init, l.var.type));
            // The tuple is taken apart where it is: each variable copies its element.
            case Destructure d -> expr(d.init);
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
                if (f.isRange()) {
                    long bounds = Layout.add(Layout.add(expr(f.from), expr(f.to)), f.step == null ? 0 : expr(f.step));
                    yield Layout.add(bounds, block(f.body, r));
                }
                // The snapshot of the collection lives as long as the loop: the value itself when the
                // collection is built by an expression (counted there), a copy when it is a variable the body
                // may change. split and words in a loop build no list: only their arguments count.
                Expr src = f.source();
                long built = f.pieces() != null ? inner(f.from) : expr(f.from);
                // A separator in a variable is copied before the first piece, as the body may change it.
                Call pieces = f.pieces();
                if (pieces != null && pieces.args.size() > 1) {
                    Expr sep = pieces.args.get(1).expr();
                    if (sep.isPlace() && sep.type instanceof Type.Str) built = Layout.add(built, Layout.size(sep.type));
                }
                long snapshot = Layout.add(built, f.snapshot ? Layout.size(src.type) : 0);
                yield Layout.add(snapshot, block(f.body, r));
            }
            case Match m -> {
                long arms = 0;
                for (Arm a : m.arms) {
                    arms = Layout.add(arms, block(a.body(), r));
                    if (a.guard() != null) arms = Layout.add(arms, expr(a.guard()));
                }
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
        // A slice read in place is a pointer and a length, not a copy.
        boolean view = e instanceof SliceExpr s && s.view;
        long own = Type.isAggregate(e.type) && !e.isPlace() && !view ? Layout.size(e.type) : 0;
        return Layout.add(own, inner(e));
    }

    /** What {@link #expr} counts for {@code e} but the value of e itself: what its parts build. */
    private static long inner(Expr e) {
        return switch (e) {
            case IntLit i -> 0;
            case FloatLit f -> 0;
            case StrLit s -> 0;
            case BoolLit b -> 0;
            case Name n -> 0;
            case FieldAccess f -> f.variant != null || f.constant != null ? 0 : expr(f.target);
            case Index i -> Layout.add(expr(i.target), expr(i.index));
            case Call c -> call(c);
            case VariantCall v when v.asCall != null -> call(v.asCall);
            case VariantCall v -> {
                long t = 0;
                for (int i = 0; i < v.args.size(); i++) {
                    Expr a = v.args.get(i).expr();
                    t = Layout.add(t, Layout.add(expr(a), stored(a, v.variant.payload.get(i))));
                }
                yield t;
            }
            case StructLit s -> {
                long t = 0;
                for (FieldInit f : s.fields) {
                    Type to = s.decl.fields.get(s.decl.fieldIndex(f.name())).type;
                    t = Layout.add(t, Layout.add(expr(f.value()), stored(f.value(), to)));
                }
                yield t;
            }
            case Convert c -> expr(c.arg);
            case Resize z -> expr(z.arg);
            case Unary u -> expr(u.operand);
            case Binary b -> Layout.add(expr(b.left), expr(b.right));
            case ArrayLit a -> {
                // A map literal's entries are (key, value) tuples.
                Type elem = a.type instanceof Type.MapT mt ? new Type.TupleT(List.of(mt.key(), mt.value()))
                    : a.type instanceof Type.SetT st ? st.elem() : Type.elementOf(a.type);
                long t = 0;
                for (Expr x : a.elems) t = Layout.add(t, Layout.add(expr(x), stored(x, elem)));
                yield t;
            }
            case Repeat r -> Layout.add(expr(r.value), stored(r.value, Type.elementOf(r.type)));
            case IfExpr i -> Layout.add(expr(i.cond), Layout.add(Layout.add(expr(i.then), stored(i.then, i.type)),
                Layout.add(expr(i.otherwise), stored(i.otherwise, i.type))));
            case MatchExpr m -> {
                // As for an if expression: what each arm builds, and its value's copy into the result.
                long t = expr(m.subject);
                for (ExprArm a : m.arms) {
                    if (a.guard() != null) t = Layout.add(t, expr(a.guard()));
                    t = Layout.add(t, Layout.add(expr(a.value()), stored(a.value(), m.type)));
                }
                yield t;
            }
            case SliceExpr s -> Layout.add(expr(s.target), Layout.add(s.from == null ? 0 : expr(s.from), s.to == null ? 0 : expr(s.to)));
            case TupleLit t -> {
                List<Type> elems = ((Type.TupleT) t.type).elems();
                long sum = 0;
                for (int i = 0; i < t.elems.size(); i++) {
                    Expr x = t.elems.get(i);
                    sum = Layout.add(sum, Layout.add(expr(x), stored(x, elems.get(i))));
                }
                yield sum;
            }
        };
    }

    private static long call(Call c) {
        long t = 0;
        for (Arg a : c.args) t = Layout.add(t, expr(a.expr()));
        if (c.fn != null) {
            // A read-only argument is passed as it is, unless it has to be widened to the parameter's type.
            for (int i = 0; i < c.args.size(); i++) {
                LocalVar p = c.fn.paramVars.get(i);
                Type given = c.args.get(i).expr().type;
                if (!p.byRef && Type.isAggregate(p.type) && !given.equals(p.type)) t = Layout.add(t, Layout.size(p.type));
            }
        } else if (c.builtin == Builtin.PUSH || c.builtin == Builtin.INSERT) {
            // What is stored is copied in: an element of a list or str, a value of a set, a key and value of a map.
            Type into = c.args.get(0).expr().type;
            Expr v = c.args.get(c.args.size() - 1).expr();
            if (into instanceof Type.MapT mt) {
                t = Layout.add(t, Layout.add(stored(c.args.get(1).expr(), mt.key()), stored(v, mt.value())));
            } else {
                t = Layout.add(t, stored(v, into instanceof Type.SetT st ? st.elem() : Type.elementOf(into)));
            }
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
        long t = e.isPlace() ? Layout.size(e.type) : 0;
        return e.type.equals(to) ? t : Layout.add(t, Layout.size(to));
    }

}
