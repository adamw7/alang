package alang.check;

import alang.ast.Ast.Program;

/**
 * Result of a successful or failed check. {@code memory} is null if there were errors. {@code expressions} checks
 * an expression written later, in the scope of a statement of the program: what a debugger evaluates.
 */
public record CheckResult(Program program, MemoryReport memory, Expressions expressions) {}
