package alang.cli;

import alang.ast.Ast.Program;
import alang.check.CheckResult;
import alang.check.Checker;
import alang.diag.Diagnostics;
import alang.diag.Source;
import alang.parse.Parser;

/** Parse + check in one call. */
public final class Frontend {
    private Frontend() {}

    public record Result(Diagnostics diags, Program program, CheckResult check) {
        public boolean ok() {
            return program != null && !diags.hasErrors();
        }
    }

    public static Result compile(Source source, long maxStack) {
        Diagnostics diags = new Diagnostics(source);
        Program program = Parser.parse(diags);
        if (program == null) return new Result(diags, null, null);
        CheckResult check = Checker.check(program, diags, maxStack);
        return new Result(diags, program, check);
    }
}
