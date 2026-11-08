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
| **No heap** | Every type has a compile-time size: `str[N]`, `list[T; N]`, `[T; N]`, structs, enums. No pointers, no `null`, no allocator. `alang check` reports the exact worst-case memory of every entry point. |
| **Deterministic** | No clock, randomness, environment, threads or I/O except `print`. No undefined behavior: overflow, bad indexes, bad conversions all trap with fixed codes. Fixed evaluation order. Only correctly rounded float operations, and exact float formatting. Same bytes out on every run, machine, compiler and locale. |
| **Always terminates** | Recursion is a compile error, and every `while` loop states an iteration `limit`. |
| **Safe to run agent code** | Tool functions take JSON in and return JSON out with no access to the host. A deterministic step budget bounds runtime. |
| **Runs anywhere** | A reference interpreter (JVM) and a C99 backend that emits heap-free, stdio-free C with byte-identical behavior. |

## Quick start

Requirements: JDK 21+ and Maven. A C compiler is optional (for `alang build`).

```sh
bin/alang run examples/hello.al         # builds target/alang.jar on first use
bin/alang test examples/rpn_calculator.al
bin/alang check examples/maze_bfs.al --memory
bin/alang tools examples/text_tools.al
bin/alang call examples/inventory.al place_order '{"sku": "B-200", "quantity": 2}'
bin/alang build examples/shapes.al -o shapes.c && cc -std=c99 -O2 shapes.c -lm -o shapes && ./shapes
```

## Commands

| Command | Purpose |
|---------|---------|
| `alang check FILE [--json] [--memory] [--max-stack B]` | Type-check; print diagnostics and the static memory bound |
| `alang run FILE [--json] [--max-steps N]` | Run `fn main()` |
| `alang test FILE [--json] [--filter TEXT]` | Run all `test "..." { }` blocks |
| `alang tools FILE` | JSON tool definitions for every `tool fn` (name, description, input_schema) |
| `alang call FILE TOOL [JSON]` | Call a tool with JSON arguments (or read them from stdin) |
| `alang build FILE [-o out.c]` | Compile to heap-free C99 |
| `alang explain CODE` | Explain a diagnostic or trap code |

Exit codes: `0` success, `1` errors / traps / failing tests, `2` usage errors.

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
- [docs/SPEC.md](docs/SPEC.md) — the language specification
- [docs/ERRORS.md](docs/ERRORS.md) — every diagnostic and trap code (generated)
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
tests/run/                programs with golden output (.out) and expected traps (.trap)
tests/errors/             programs that must fail with specific diagnostic codes
tests/examples/           golden output of the examples
```

## Development

```sh
mvn test                          # 240+ tests: conformance, examples, determinism, fuzzing, C backend, architecture
mvn test -Dtest=ArchitectureTest  # just the ArchUnit rules (package layers, no clock/locale/I/O in the core)
mvn test -Dalang.fuzz=3000        # a longer fuzzing campaign
bin/alang explain markdown > docs/ERRORS.md   # regenerate the error catalog
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify` on JDK 21 with gcc
and clang on Linux and macOS, smoke-tests the `bin/alang` commands against the
packaged jar, and runs a 3000-mutation fuzzing campaign.

Renovate ([`renovate.json`](renovate.json)) opens pull requests for Maven dependencies, Maven
plugins and GitHub Actions. The ANTLR tool and runtime are updated together, the Maven plugins
in one pull request, and the workflow's actions in another. Renovate merges each of its pull
requests once every CI check on it has passed, rebasing it onto `main` first if it has fallen
behind.

## Status

Version 0.1. Not yet included: modules/imports, generics, slices, a formatter, and a
standard library beyond the builtins.

## License

MIT, see [LICENSE](LICENSE).
