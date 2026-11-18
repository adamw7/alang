/**
 * The supported Java API for embedding ALang in a JVM host: compile a program, read its diagnostics, list its
 * tools with their JSON schemas, and call them with JSON arguments under a step budget and a time limit.
 *
 * <p>This package is the only public API of the jar. Every other package ({@code alang.cli},
 * {@code alang.check}, {@code alang.interp}, ...) is internal and may change in any release; its public
 * classes are public only so the toolchain's own packages can reach them.
 *
 * <pre>{@code
 * Compilation c = Alang.compile(Path.of("bill.al"));
 * if (!c.ok()) throw new IllegalStateException(c.diagnosticsText());
 * Program program = c.program();
 * for (Tool t : program.tools()) System.out.println(t.toJson());     // hand these to the model
 * CallResult r = program.call("split_bill", "{\"total_cents\": 1000, \"people\": 3}",
 *     CallOptions.defaults().maxSteps(1_000_000).timeoutMillis(2_000));
 * r.ok();          // true
 * r.resultJson();  // [334, 333, 333]
 * r.toJson();      // what `alang call` prints, for a tool_result block
 * }</pre>
 *
 * <p>A {@link alang.api.Program} is immutable once compiled: any number of threads may call its tools at once,
 * and each call is deterministic, so the same arguments always give the same {@link alang.api.CallResult}
 * (unless the call was stopped by its time limit or cancelled).
 */
package alang.api;
