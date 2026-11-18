package alang.check;

import alang.ast.Ast.*;
import alang.diag.Diagnostics;
import java.util.List;

/** Checks an expression as if it were written in a routine of a checked program: `print` and `break ... if` of a debugger. */
public interface Expressions {
    /**
     * Checks {@code e} as if it were written in {@code routine} where {@code visible} are the variables in scope, so
     * that it can then be evaluated; a call with a `mut` argument is refused, so it cannot change them. Returns the
     * frame to evaluate it in: a routine whose locals are those of {@code routine} and then any the expression
     * binds, each with its slot. Returns null when {@code e} has errors, which go to {@code diags}.
     */
    Routine check(Expr e, Routine routine, List<LocalVar> visible, Diagnostics diags);
}
