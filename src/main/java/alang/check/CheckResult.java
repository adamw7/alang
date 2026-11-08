package alang.check;

import alang.ast.Ast.Program;

/** Result of a successful or failed check. {@code memory} is null if there were errors. */
public record CheckResult(Program program, MemoryReport memory) {}
