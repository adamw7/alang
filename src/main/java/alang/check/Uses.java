package alang.check;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.diag.Span;
import alang.types.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * What the statements of a checked routine read and change, for two uses: whether a `for` loop's body can
 * change the variable it iterates (so the loop needs a copy of it), and the warnings for code whose work is
 * lost: locals never used (W0304), locals changed but never read (W0305), and functions never called
 * (W0306).
 *
 * <p>Locals can change only where the source says so: an assignment to a place rooted at them, or a `mut`
 * argument rooted at them. A `mut` reference is never stored, and a variable cannot be passed as `mut`
 * together with any other aggregate argument (E0208), so nothing else reaches a variable's storage.
 */
final class Uses {
    private Uses() {}

    /** Whether {@code body} can change {@code v}: it assigns to, or passes as `mut`, a place rooted at v. */
    static boolean changes(Block body, LocalVar v) {
        Uses.Scan s = new Uses.Scan();
        s.block(body);
        return s.changed.contains(v);
    }

    /**
     * Reports the warnings for unused code. Locals and match bindings whose name starts with `_` are left
     * alone, and so are functions whose name does, and those of imported files: a library has functions its
     * importer does not call. {@code text} gives an expression's source text.
     */
    static void warnUnused(Program program, Diagnostics diags, Function<Expr, String> text) {
        Set<FnDecl> called = new HashSet<>();
        for (Item item : program.items) {
            if (item instanceof Routine r) {
                // A call of a function that takes any capacity runs an instance of it.
                for (FnDecl f : r.callees) called.add(f.origin != null ? f.origin : f);
            }
        }
        for (Item item : program.items) {
            if (!(item instanceof Routine r)) continue;
            Scan s = new Scan();
            s.block(r.body);
            if (r instanceof FnDecl f) {
                for (Expr e : f.requires) s.read(e);
                for (Expr e : f.ensures) s.read(e);
                boolean main = r.span.file() == null || program.files.indexOf(r.span.file()) <= 0;
                if (main && !f.tool && !f.name.equals("main") && !f.name.startsWith("_") && !called.contains(f)) {
                    diags.warning(Code.W0306, f.nameSpan, "function `" + f.name + "` is never called",
                        "call it, test it from a test block, or remove it");
                }
            }
            for (Let l : s.lets) {
                LocalVar v = l.var;
                if (v == null || v.name.startsWith("_") || s.read.contains(v)) continue;
                if (!s.changed.contains(v)) {
                    diags.warning(Code.W0304, l.nameSpan, "`" + v.name + "` is never used",
                        "remove it; a call it holds that is needed for what it does can be a statement of its own");
                } else if (l.init.isPlace() && Type.isAggregate(l.init.type)) {
                    String from = text.apply(l.init);
                    diags.warning(Code.W0305, l.nameSpan, "`" + v.name + "` is changed but never read",
                        "`" + v.name + "` is a copy of `" + from + "`, so changing it leaves `" + from + "` as it was; "
                            + "change `" + from + "` itself instead");
                } else {
                    diags.warning(Code.W0305, l.nameSpan, "`" + v.name + "` is changed but never read",
                        "what it is given is lost: use its value, or remove it");
                }
            }
            for (VariantPat p : s.patterns) {
                for (int i = 0; i < p.vars.size(); i++) {
                    LocalVar v = p.vars.get(i);
                    if (v == null || v.name.startsWith("_") || s.read.contains(v)) continue;
                    Span at = p.bindingSpans.get(i);
                    diags.warning(Code.W0304, at, "`" + v.name + "` is never used", "write _ in its place");
                }
            }
        }
    }

    /** One pass over statements: the locals read, the locals changed, the lets and the variant patterns. */
    private static final class Scan {
        final Set<LocalVar> read = new HashSet<>();
        final Set<LocalVar> changed = new HashSet<>();
        final List<Let> lets = new ArrayList<>();
        final List<VariantPat> patterns = new ArrayList<>();

        void block(Block b) {
            for (Stmt s : b.stmts()) stmt(s);
        }

        void stmt(Stmt s) {
            switch (s) {
                case Let l -> {
                    lets.add(l);
                    read(l.init);
                }
                case Assign a -> {
                    // `x += 1` uses x only to change it, so it is not a read of x.
                    written(a.target);
                    read(a.value);
                }
                case If i -> {
                    for (Expr c : i.conds) read(c);
                    for (Block b : i.blocks) block(b);
                    if (i.elseBlock != null) block(i.elseBlock);
                }
                case While w -> {
                    read(w.cond);
                    read(w.limit);
                    block(w.body);
                }
                case For f -> {
                    read(f.from);
                    if (f.to != null) read(f.to);
                    block(f.body);
                }
                case Match m -> {
                    read(m.subject);
                    for (Arm a : m.arms) {
                        if (a.pattern() instanceof VariantPat p) patterns.add(p);
                        block(a.body());
                    }
                }
                case Return r -> {
                    if (r.value != null) read(r.value);
                }
                case Assert a -> read(a.cond);
                case ExprStmt e -> read(e.expr);
                case Break b -> { }
                case Continue c -> { }
            }
        }

        /** A place that is stored to: its root changes, and its indexes are read. */
        void written(Expr place) {
            switch (place) {
                case Name n -> {
                    if (n.local != null) changed.add(n.local);
                }
                case FieldAccess f -> written(f.target);
                case Index i -> {
                    written(i.target);
                    read(i.index);
                }
                default -> read(place);
            }
        }

        void read(Expr e) {
            switch (e) {
                case Name n -> {
                    if (n.local != null) read.add(n.local);
                }
                case FieldAccess f -> read(f.target);
                case Index i -> {
                    read(i.target);
                    read(i.index);
                }
                case Call c -> {
                    // A user function may read what it is given as `mut`, and so do pop and remove, which
                    // return part of it; the other builtins that take `mut` only change it.
                    boolean reads = c.builtin == null || c.builtin == Builtin.POP || c.builtin == Builtin.REMOVE;
                    for (Arg a : c.args) {
                        if (a.mut()) {
                            written(a.expr());
                            if (reads) read(a.expr());
                        } else {
                            read(a.expr());
                        }
                    }
                }
                case VariantCall v -> {
                    for (Arg a : v.args) read(a.expr());
                }
                case StructLit s -> {
                    for (FieldInit f : s.fields) read(f.value());
                }
                case Convert c -> read(c.arg);
                case Resize r -> read(r.arg);
                case Unary u -> read(u.operand);
                case Binary b -> {
                    read(b.left);
                    read(b.right);
                }
                case ArrayLit a -> {
                    for (Expr x : a.elems) read(x);
                }
                case Repeat r -> read(r.value);
                case IntLit i -> { }
                case FloatLit f -> { }
                case StrLit s -> { }
                case BoolLit b -> { }
            }
        }
    }
}
