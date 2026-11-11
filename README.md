# ALang

[![CI](https://github.com/adamw7/alang/actions/workflows/ci.yml/badge.svg)](https://github.com/adamw7/alang/actions/workflows/ci.yml)

Agentic language. For agents. Not for humans. Does not use heaps and other source of problems.

**An agent-first programming language: no heap, fully deterministic, always terminates.** A small,
statically typed language that AI agents write and hosts run safely. The parser is generated from one
ANTLR 4 grammar, [`grammar/ALang.g4`](grammar/ALang.g4), on every build.

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
| **Easy for agents** | One way to do things, explicit types, Rust-like syntax, no precedence puzzles, no shadowing. Every diagnostic has a stable code, an exact location and a fix hint, also as JSON. Built-in `test` blocks and `requires`/`ensures` contracts. |
| **No heap** | Every type has a compile-time size (`str[N]`, `list[T; N]`, `[T; N]`, structs, enums); no pointers, `null` or allocator. `alang check` bounds the worst-case memory of every entry point and rejects a program over its budget (8 MiB by default). |
| **Deterministic** | No clock, randomness, environment, threads or I/O except `print`. No undefined behavior: overflow, bad indexes and bad conversions trap with fixed codes. Fixed evaluation order, only correctly rounded floats, exact formatting. The tests run every program repeatedly and under several locales, and compare the C backend byte for byte with the interpreter under gcc and clang at `-O0` to `-O3`. |
| **Always terminates** | Recursion is a compile error; every `while` loop states an iteration `limit`. |
| **Safe to run agent code** | Tool functions take JSON in and return JSON out with no access to the host; a deterministic step budget bounds runtime. |
| **Runs anywhere** | A reference interpreter (JVM) and a C99 backend that emits heap-free, stdio-free C with byte-identical behavior. |

[docs/COMPARISON.md](docs/COMPARISON.md) sets these guarantees beside Python, JavaScript, Go, Rust, C
and Java, with programs you can run.

## Quick start

Requires JDK 21+ and Maven; a C compiler is optional (for `alang build`).

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
| `alang check FILE [--json] [--memory] [--max-stack B]` | Type-check; print diagnostics and the static memory bound (budget 8 MiB unless `--max-stack` sets another) |
| `alang run FILE [--json] [--max-steps N] [--max-stack B]` | Run `fn main()` |
| `alang test FILE [--json] [--filter TEXT] [--max-steps N] [--max-stack B]` | Run all `test "..." { }` blocks; a filter that matches no test fails |
| `alang tools FILE [--json] [--max-steps N] [--max-stack B]` | JSON tool definitions for every `tool fn` (name, description, input_schema, and input_examples from the tests' calls) |
| `alang call FILE TOOL [JSON \| -] [--max-steps N] [--max-stack B]` | Call a tool with JSON arguments; `-` reads them from stdin, none means `{}` |
| `alang build FILE [-o out.c] [--max-stack B]` | Compile to heap-free C99 |
| `alang explain CODE \| --all \| --markdown` | Explain a diagnostic or trap code, or all of them |

Exit codes: `0` success, `1` errors, traps or failing tests, `2` usage errors (including an option the
command does not take, or an extra argument). With `--json`, and always for `call`, errors are JSON on
stdout too; `call`'s error codes are `usage_error`, `compile_error`, `bad_tool`, `bad_json`,
`bad_arguments`, `bad_result` and the trap codes (`R....`).

## Diagnostics built for agents

```
error[E0215]: a call with `mut` arguments must be its own statement or the whole right-hand side of let/var/assignment/return
 --> oops.al:4:15
  |
4 |         print(pop(mut stack));
  |               ^^^^^^^^^^^^^^
  = hint: store the result first: let value: i32 = pop(mut stack); then use `value`
```

With `--json`, the report is one line of JSON that names the source `file` once, and each diagnostic
has `severity`, `code`, `title`, `message`, `line`, `column`, `length`, `source_line` and `hint`. A trap
gives its `line`, `column` and `source_line` the same way. Habits from other languages (`&&`, `x++`, `Color::Red`, `Point { x: 1 }`,
`xs.len()`, `let x = 5`, `while` without a limit, `null`, generics, imports, tuples, slices, `if` as an
expression, match guards, `sort`, `pow`, ...) are answered with the ALang spelling.
See [docs/ERRORS.md](docs/ERRORS.md).

## Tool results in a prompt

`alang call` prints JSON, and a harness should keep reading `ok`, `result` and `error.code` from it. For
the copy it shows a model, [`harness/toon.py`](harness/toon.py) turns the JSON into
[TOON](https://github.com/toon-format/spec): the same data with indentation instead of braces, less
quoting, and a list of structs as a table that names the fields once.

```
$ bin/alang call words.al word_counts '{"text": "the cat saw the dog"}'
{"ok": true, "result": [{"word": "the", "count": 2}, {"word": "cat", "count": 1}, {"word": "saw", "count": 1}, {"word": "dog", "count": 1}], "stdout": ""}
$ bin/alang call words.al word_counts '{"text": "the cat saw the dog"}' | python3 harness/toon.py
ok: true
result[4]{word,count}:
  the,2
  cat,1
  saw,1
  dog,1
stdout: ""
```

It saves the most on long lists of structs. On a small or nested result, compact JSON costs about the
same, so measure before switching a harness over. `--delimiter tab` saves a little more; from Python,
`from toon import encode`. It needs only the standard library, and its output is the reference encoder's
(TOON spec v4.4), except that integers past 2^53 stay exact.

## Documentation

- [AGENTS.md](AGENTS.md): the one-page guide for AI agents writing ALang (start here)
- [docs/COMPARISON.md](docs/COMPARISON.md): ALang next to Python, JavaScript, Go, Rust and C
- [docs/SPEC.md](docs/SPEC.md): the language specification
- [docs/ERRORS.md](docs/ERRORS.md): every diagnostic and trap code (generated)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): C4 diagrams of the system, the toolchain and a tool call
- [bench/](bench): do models write more correct tools in ALang or in Python?
- [harness/toon.py](harness/toon.py): tool results as TOON for a model's prompt
- [examples/](examples): complete programs, each with tests

`src/main/java/alang/` holds the toolchain, one package per component (see the architecture);
`tests/run/` has programs with golden output (`.out`) and expected traps (`.trap`), `tests/errors/`
programs that must fail with given codes and exact messages (`.err`), `tests/stack/` programs that must
fit their memory bound when compiled to C, and `tests/examples/` the examples' golden output.

## Development

```sh
mvn verify                                    # every test: conformance, docs, determinism, fuzzing, C backend, ArchUnit rules
mvn test -Dalang.fuzz=3000                    # a longer fuzzing campaign
mvn test-compile pitest:mutationCoverage      # mutation testing (minutes; report in target/pit-reports)
python3 bench/bench.py selftest               # the benchmark's tasks, grader and feedback loop, offline
python3 harness/test_toon.py                  # the JSON-to-TOON converter
bin/alang explain markdown > docs/ERRORS.md   # regenerate the error catalog
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `mvn verify` with gcc and clang on
Linux and macOS, smoke-tests `bin/alang` against the packaged jar, and runs the TOON converter's tests,
the benchmark self-test and a 3000-mutation fuzzing campaign. Every test, CI step and job has a time limit, so a hang fails fast.
Mutation testing is too slow for every push: it runs every Saturday on `main`
([`.github/workflows/mutation.yml`](.github/workflows/mutation.yml), or on demand from the Actions tab),
uploads the `pit-report` artifact, and fails when the mutation score drops below 74% or the test
strength below 85%; [`pom.xml`](pom.xml) explains what is mutated and why. Renovate keeps the Maven
dependencies, plugins and GitHub Actions current and merges its pull requests once CI passes;
mutation testing is not part of that, so a PIT update is first exercised by the next Saturday run.

## Status

Version 0.1. Not yet included: modules/imports, generics, slices, a formatter, and a standard library
beyond the builtins.

## License

MIT, see [LICENSE](LICENSE).
