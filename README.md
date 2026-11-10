# ALang

[![CI](https://github.com/adamw7/alang/actions/workflows/ci.yml/badge.svg)](https://github.com/adamw7/alang/actions/workflows/ci.yml)

Agentic language. For agents. Not for humans. Does not use heaps and other source of problems.

**An agent-first programming language: no heap, fully deterministic, always terminates.**

ALang is a small, statically typed language designed to be written by AI agents and executed
safely. Its grammar is a single ANTLR 4 file, [`grammar/ALang.g4`](grammar/ALang.g4), from which
the toolchain's parser is generated on every build.

```alang
/// Splits a bill evenly; the remainder goes to the first payers.
tool fn split_bill(total_cents: i64, people: i64) -> list[i64; 16]
    requires total_cents >= 0
    requires people > 0 and people <= 16
{
    var shares: list[i64; 16] = [];
    for i in 0..people {
        var share: i64 = total_cents / people;
        if i < total_cents % people {
            share += 1;
        }
        push(mut shares, share);
    }
    return shares;
}

fn main() {
    print(split_bill(1000, 3));
}

test "shares add up to the total" {
    let shares: list[i64; 16] = split_bill(1001, 4);
    var sum: i64 = 0;
    for s in shares {
        sum += s;
    }
    assert sum == 1001;
}
```

```
$ bin/alang run bill.al
[334, 333, 333]
$ bin/alang call bill.al split_bill '{"total_cents": 1000, "people": 3}'
{"ok": true, "result": [334, 333, 333], "stdout": ""}
```

## Why

| Property | How ALang guarantees it |
|----------|-------------------------|
| **Easy for agents** | One way to do things; explicit types; familiar Rust-like syntax; no precedence puzzles; no shadowing. Every diagnostic has a stable code, an exact location and a concrete fix hint, also as JSON. Built-in `test` blocks and contracts (`requires` / `ensures`). |
| **No heap** | Every type has a compile-time size: `str[N]`, `list[T; N]`, `[T; N]`, structs, enums. No pointers, no `null`, no allocator. `alang check` reports the worst-case memory of every entry point and rejects a program over its budget (8 MiB by default). |
| **Deterministic** | No clock, randomness, environment, threads or I/O except `print`. No undefined behavior: overflow, bad indexes, bad conversions all trap with fixed codes. Fixed evaluation order. Only correctly rounded float operations, and exact float formatting. Same bytes out on every run, machine, compiler and locale. |
| **Always terminates** | Recursion is a compile error, and every `while` loop states an iteration `limit`. |
| **Safe to run agent code** | Tool functions take JSON in and return JSON out with no access to the host. A deterministic step budget bounds runtime. |
| **Runs anywhere** | A reference interpreter (JVM) and a C99 backend that emits heap-free, stdio-free C with byte-identical behavior. |

[docs/COMPARISON.md](docs/COMPARISON.md) puts these guarantees side by side with Python, JavaScript,
Go, Rust, C and Java, using programs you can run.

## Quick start

Requirements: JDK 21+ and Maven. A C compiler is optional (for `alang build`).

```sh
bin/alang run examples/hello.al         # builds target/alang.jar on first use and after source changes
bin/alang test examples/rpn_calculator.al
bin/alang check examples/maze_bfs.al --memory
bin/alang tools examples/text_tools.al
bin/alang call examples/inventory.al place_order '{"sku": "B-200", "quantity": 2}'
bin/alang build examples/shapes.al -o shapes.c && cc -std=c99 -O2 shapes.c -lm -o shapes && ./shapes
```

## Commands

| Command | Purpose |
|---------|---------|
| `alang check FILE [--json] [--memory] [--max-stack B]` | Type-check; print diagnostics and the static memory bound (8 MiB budget unless `--max-stack` sets another) |
| `alang run FILE [--json] [--max-steps N] [--max-stack B]` | Run `fn main()` |
| `alang test FILE [--json] [--filter TEXT] [--max-steps N] [--max-stack B]` | Run all `test "..." { }` blocks; a filter that matches no test fails |
| `alang tools FILE [--json] [--max-stack B]` | JSON tool definitions for every `tool fn` (name, description, input_schema) |
| `alang call FILE TOOL [JSON \| -] [--max-steps N] [--max-stack B]` | Call a tool with JSON arguments; `-` reads them from stdin, and none means `{}` |
| `alang build FILE [-o out.c] [--max-stack B]` | Compile to heap-free C99 |
| `alang explain CODE \| --all \| --markdown` | Explain a diagnostic or trap code, or all of them |

Exit codes: `0` success, `1` errors / traps / failing tests, `2` usage errors. An option a command
does not take, or an extra argument, is a usage error. With `--json`, and always for `call`, errors
are reported as JSON on stdout too; `call`'s error codes are `usage_error`, `compile_error`,
`bad_tool`, `bad_json`, `bad_arguments`, `bad_result` and the trap codes (`R....`).

## Diagnostics built for agents

```
error[E0215]: a call with `mut` arguments must be its own statement or the whole right-hand side of let/var/assignment/return
 --> oops.al:4:15
  |
4 |         print(pop(mut stack));
  |               ^^^^^^^^^^^^^^
  = hint: store the result first: let value: i32 = pop(mut stack); then use `value`
```

With `--json`, each diagnostic is an object with `code`, `title`, `message`, `file`, `line`,
`column`, `length`, `source_line` and `hint`. Common habits from other languages (`&&`, `x++`,
`Color::Red`, `Point { x: 1 }`, `xs.len()`, `let x = 5`, `while` without a limit, `null`, ...)
are recognized and answered with the ALang spelling. See [docs/ERRORS.md](docs/ERRORS.md).

## Determinism, verified

The test suite does not just claim determinism, it checks it:

- every program is run repeatedly and under several host locales (`de-DE`, `tr-TR`, Arabic and
  Devanagari digits) and must produce identical diagnostics, memory reports, output and traps;
- the C backend is compared byte-for-byte with the interpreter (stdout, trap code, message and
  location) under gcc and clang at `-O0`/`-O2`/`-O3`, including with `-ffp-contract=fast`
  forced on the command line;
- generated object files are checked to reference no allocation or stdio functions;
- a seeded mutation fuzzer feeds thousands of corrupted programs through the checker and the
  interpreter, which must either report diagnostics or run to completion or a defined trap.

## Documentation

- [AGENTS.md](AGENTS.md) — the one-page guide for AI agents writing ALang (start here)
- [docs/COMPARISON.md](docs/COMPARISON.md) — how ALang compares with Python, JavaScript, Go, Rust and C
- [bench/](bench) — a benchmark: do models write more correct tools in ALang or in Python?
- [docs/SPEC.md](docs/SPEC.md) — the language specification
- [docs/ERRORS.md](docs/ERRORS.md) — every diagnostic and trap code (generated)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — C4 diagrams: system context, containers, the toolchain's components, a tool call
- [grammar/ALang.g4](grammar/ALang.g4) — the grammar
- [examples/](examples) — complete programs, each with tests

## Repository layout

```
grammar/ALang.g4          the grammar (ANTLR 4, target-independent)
src/main/java/alang/
  parse/                  parse tree -> AST, syntax error hints
  check/                  type checker, memory bound, recursion ban
  interp/                 reference interpreter, value formatting, JSON mapping
  cgen/                   C99 backend (runtime prelude in src/main/resources)
  cli/                    the `alang` command
examples/                 example programs
bench/                    ALang vs Python benchmark for model-written tools
tests/run/                programs with golden output (.out) and expected traps (.trap)
tests/errors/             programs that must fail with specific diagnostic codes (and exact messages, .err)
tests/examples/           golden output of the examples
```

## Development

```sh
mvn test                              # 270+ tests: conformance, examples, determinism, fuzzing, C backend, architecture
mvn test -Dtest='*ArchitectureTest'   # just the ArchUnit rules (layers, determinism, I/O at the edge, test conventions)
mvn test -Dalang.fuzz=3000            # a longer fuzzing campaign
python3 bench/bench.py selftest       # the benchmark's tasks, grader and feedback loop, offline
mvn test-compile pitest:mutationCoverage      # mutation testing of the toolchain (see below)
bin/alang explain markdown > docs/ERRORS.md   # regenerate the error catalog
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify` on JDK 21 with gcc
and clang on Linux and macOS, smoke-tests the `bin/alang` commands against the
packaged jar, runs the benchmark self-test on Linux, runs a 3000-mutation fuzzing campaign, and
runs mutation testing, uploading the report as the `pit-report` artifact.

Every test has a time limit, so a hang fails fast instead of stalling CI. A test fails after 10 s,
even if it never checks for interrupts
([`junit-platform.properties`](src/test/resources/junit-platform.properties); dynamic tests are
built with `Harness.dynamicTest`, which an ArchUnit rule enforces). Surefire kills the test JVM if
the whole test run takes longer than 180 s (`surefire.timeout` in `pom.xml`). Each CI step and job
also has a limit of a few minutes.

Renovate ([`renovate.json`](renovate.json)) opens pull requests for Maven dependencies, Maven
plugins and GitHub Actions. The ANTLR tool and runtime are updated together, PIT and its JUnit 5
plugin together, the Maven plugins in one pull request, and the workflow's actions in another.
Renovate merges each of its pull requests once every CI check on it has passed, rebasing it onto
`main` first if it has fallen behind.

### Mutation testing

The fuzzer mutates ALang *programs* to show that the toolchain never crashes. Mutation testing
checks the *tests* instead: [PIT](https://pitest.org) makes small changes to the toolchain's
compiled code (flips a comparison, removes a call, returns a constant) and runs the tests that
cover the changed line against each of these mutants. A mutant that no test notices points at
behavior the suite does not pin down.

```sh
mvn test-compile pitest:mutationCoverage                      # about 2,900 mutants, 3 minutes on 4 cores
mvn test-compile pitest:mutationCoverage -Dpitest.threads=8   # parallel test JVMs: default 4, at most one per core
```

PIT prints how many mutants are done every 30 seconds. The report,
`target/pit-reports/index.html`, shows every surviving mutant on its source line.
Every class under `alang` is mutated except the ANTLR-generated parser and the C backend: only
`CBackendTest` can catch a C backend mutant, and it compiles every program with gcc and clang,
which would turn minutes into hours. That test and the ArchUnit rules (`*ArchitectureTest`), which
read class files from disk and so never see a mutant, are left out of the run. So are `FuzzTest`
and `DeterminismTest`: they check only that the toolchain neither crashes nor changes its mind, so
they notice a mutant only when it crashes, and the exact-output tests notice those too
(`tests/errors` with `.err` files pins every message and hint). They were two thirds of the run.

The run fails when the mutation score (mutants killed out of all mutants) drops below 74% or the
test strength (killed out of those that some test covers) below 85%. The baseline is 76% and
86%; raise the thresholds in [`pom.xml`](pom.xml) as the tests improve. The weakest areas are
the CLI and the JSON side of tool calls (`Main`, `JsonValues`), which the unit tests barely
reach, and memory layout (`Layout`), whose exact numbers few tests assert.

## Status

Version 0.1. Not yet included: modules/imports, generics, slices, a formatter, and a
standard library beyond the builtins.

## License

MIT, see [LICENSE](LICENSE).
