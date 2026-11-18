package alang.interp;

import alang.ast.Ast.*;
import alang.diag.*;
import alang.types.Type;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * One call of a function, or the run of a test: its routine, the slots of its variables, and for a debugger
 * the statement it is at and the variables in scope there. A debugger reads it; only the interpreter changes it.
 */
public final class Frame {
    private final String name;
    private final Span site;
    private final Routine routine;
    private final Object[] slots;
    /** The locals that hold a value and are in scope, in the order they were declared; kept only with a debugger. */
    final List<LocalVar> live = new ArrayList<>();
    /** How many of {@link #live} there were when the statement being run started. */
    int mark;
    /** The statement being run: in an outer frame, the one making the call. */
    Span at;
    /** The last statement started in this frame, or null: a line breakpoint stops where the run reaches a line anew. */
    Span last;

    Frame(String name, Span site, Routine routine, Object[] slots) {
        this.name = name;
        this.site = site;
        this.routine = routine;
        this.slots = slots;
        this.at = site;
    }

    void bind(LocalVar v) {
        if (!live.contains(v)) live.add(v);
    }

    void truncate(int n) {
        while (live.size() > n) live.remove(live.size() - 1);
    }

    /** "fn name" or "test \"name\"", as a trap's trace names it. */
    public String name() {
        return name;
    }

    /** The function or test. */
    public Routine routine() {
        return routine;
    }

    /** Where it was called from: for the entry point, its own declaration. */
    public Span site() {
        return site;
    }

    /** The statement being run; in an outer frame, the one that made the call. */
    public Span at() {
        return at;
    }

    /** A variable's name, type and value, as a trap message writes the value. */
    public record Variable(String name, Type type, String value) {}

    /** The parameters, then each variable in scope that holds a value, in the order they were declared. */
    public List<Variable> variables() {
        List<Variable> out = new ArrayList<>();
        for (LocalVar v : visible()) out.add(new Variable(v.name, v.type, text(value(v), v.type)));
        return out;
    }

    private List<LocalVar> visible() {
        List<LocalVar> vars = new ArrayList<>();
        if (routine instanceof FnDecl f) {
            for (LocalVar p : f.paramVars) {
                if (slots[p.slot] != null) vars.add(p);
            }
        }
        for (LocalVar v : live) {
            if (!vars.contains(v) && slots[v.slot] != null) vars.add(v);
        }
        return vars;
    }

    private Object value(LocalVar v) {
        Object o = slots[v.slot];
        return v.byRef ? ((Interpreter.Ref) o).get() : o;
    }

    /**
     * The variables in scope, for checking an expression typed at a stop: the parameters, then the variables declared
     * so far, in order.
     */
    public List<LocalVar> scope() {
        return visible();
    }

    /**
     * Evaluates {@code e}, checked for this frame into {@code checked} (see alang.check.Expressions), in a copy of the
     * frame, within {@code maxSteps}; what it prints goes to {@code out}. A trap in it is thrown, and changes nothing.
     */
    public Object evaluate(Expr e, Routine checked, long maxSteps, OutputStream out) {
        Object[] copy = Arrays.copyOf(slots, Math.max(slots.length, checked.locals.size()));
        Source src = e.span.file();
        return Interpreter.evaluate(src, e, copy, maxSteps, out);
    }

    /** The text of a value, as {@link #variables()} writes it. */
    public static String text(Object v, Type t) {
        return new String(Format.toBytes(v, t), StandardCharsets.UTF_8);
    }
}
