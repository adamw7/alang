# ALang

[![CI](https://github.com/adamw7/alang/actions/workflows/ci.yml/badge.svg)](https://github.com/adamw7/alang/actions/workflows/ci.yml)

**An agent-first programming language: no heap, fully deterministic, always terminates.** A small,
statically typed language that AI agents write and hosts run safely. The parser is generated from one
ANTLR 4 grammar, [`grammar/ALang.g4`](grammar/ALang.g4).

Demos (under 2 minutes each, narrated, all output real): [docs/demo/demo.mp4](docs/demo/demo.mp4) shows
Claude Code writing, checking, fixing, testing and calling an ALang tool;
[docs/demo/why.mp4](docs/demo/why.mp4) sets six bugs that JavaScript, C and Python let through beside the
same programs in ALang. `docs/demo/make_*.js` re-render them.

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
{"ok": true, "result": [334, 333, 333], "stdout": "", "steps": 16}
```

Install it (below), then give your agent [AGENTS.md](AGENTS.md).

## Why

| Property | How |
|----------|-----|
| **Easy for agents** | One way to do things, explicit types, no precedence puzzles, no shadowing. Every diagnostic has a stable code, location and fix hint, also as JSON. Built-in `test` blocks and `requires`/`ensures` contracts. |
| **No heap** | Every type has a compile-time size; no pointers, `null` or allocator. `alang check` bounds each entry point's worst-case memory (8 MiB budget by default). |
| **Deterministic** | No clock, randomness, environment, threads or I/O but `print`; input arrives whole. No undefined behavior: overflow, bad indexes and conversions trap with fixed codes. Floats are correctly rounded, `sin`/`exp`/`pow` included. Tests compare the C backend byte for byte with the interpreter under gcc and clang, `-O0` to `-O3`, and across locales. |
| **Always terminates** | No recursion; every `while` states a `limit`. |
| **Safe to run** | Tools take JSON in and give JSON out with no host access, within a deterministic step budget (backed by `--timeout`). |
| **Runs anywhere** | A JVM interpreter and a C99 backend emitting heap-free, stdio-free C. |

[docs/COMPARISON.md](docs/COMPARISON.md) compares these with Python, JavaScript, Go, Rust, C and Java.

## Install

```sh
curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh   # Linux, macOS
```

It installs into `~/.alang`, links `~/.local/bin/alang`, and verifies downloads against `SHA256SUMS`
(`ALANG_HOME`, `ALANG_BIN_DIR`, `ALANG_VERSION` override; see [install.sh](install.sh)). Linux x64/arm64
(glibc 2.35+) and Apple-silicon macOS get a native binary; elsewhere it installs the Java 21+ archive.

Or unpack `alang-VERSION.tar.gz`/`.zip` from the [latest release](https://github.com/adamw7/alang/releases/latest)
(Java 21+): `bin/alang` (`bin\alang.cmd` on Windows, where JSON arguments are best passed on stdin:
`alang call prog.al tool - < args.json`), `lib/alang.jar`, AGENTS.md, docs and examples. Releases also carry
native binaries (`alang-{linux-x64,linux-arm64,macos-arm64}.tar.gz`, `alang-windows-x64.zip`), the runnable jar,
and AGENTS.md at <https://github.com/adamw7/alang/releases/latest/download/AGENTS.md>. For JVM hosts the jar is
in GitHub Packages as `io.github.adamw7:alang`.

For VS Code, [editors/vscode](editors/vscode) adds highlighting, `alang check` diagnostics with quick fixes,
formatting, builtin hovers, and Run / Run test / Debug lenses.

## Quick start

In a clone, `bin/alang` builds the jar itself (JDK 21+ and Maven). A C compiler is needed only for `build`.

```sh
bin/alang run examples/hello.al
bin/alang test examples/rpn_calculator.al
bin/alang check examples/maze_bfs.al --memory
bin/alang tools examples/text_tools.al
bin/alang call examples/inventory.al place_order '{"sku": "B-200", "quantity": 2}'
bin/alang build examples/shapes.al -o shapes.c && cc -std=c99 -O2 shapes.c -lm -o shapes && ./shapes
```

## Commands

| Command | Purpose |
|---------|---------|
| `check FILE [--json] [--memory]` | Type-check; diagnostics and the static memory bound |
| `run FILE` | Run `fn main()`; `fn main(input: str[N])` gets all of stdin |
| `test FILE [--filter TEXT]` | Run the `test` blocks (`test "..." traps R0006 { }` must trap) |
| `tools FILE` | JSON tool definitions for every `tool fn` |
| `call FILE TOOL [JSON \| -] [--timeout S]` | Call a tool; `-` reads the arguments from stdin |
| `fuzz FILE` | Call every tool, and a `main` that reads input, with arguments at their limits |
| `serve FILE [--watch] [--http ADDR]` | Serve every tool over MCP |
| `build FILE [-o out.c]` | Compile to heap-free C99 |
| `fix FILE [--write]` | Apply every diagnostic's mechanical `= fix:` |
| `fmt FILE [--write \| --check]` | Lay the file out the one way |
| `debug FILE [--break LOC]... [--test NAME \| --call TOOL]` | Debugger driven by commands on stdin ([SPEC.md §13](docs/SPEC.md#13-the-debugger)) |
| `deps FILE [--lock]` | List the packages `alang.json` declares; `--lock` pins each to the SHA-256 of its files |
| `explain CODE \| --all \| --markdown` | Explain a diagnostic or trap code |

Most commands take `--json`, `--max-steps N` and `--max-stack B`. Exit codes: `0` success, `1` errors,
traps or failing tests, `2` usage errors. `call` always answers in JSON; its error codes are `usage_error`,
`compile_error`, `bad_tool`, `bad_json`, `bad_arguments`, `bad_result`, `timeout` and the trap codes.

## Standard library

Builtins that work on any type and capacity, identically in the interpreter and the C backend
([SPEC.md §9](docs/SPEC.md#9-builtins-and-the-standard-library); the list is in [AGENTS.md](AGENTS.md#the-standard-library)).
A user function takes any capacity with `_`: `fn total(xs: list[i64; _])`.

```alang
/// The distinct words of a text in alphabetical order, lower-cased: the first 32, of up to 16 bytes.
tool fn vocabulary(text: str[256]) -> list[str[16]; 32] {
    var unique: list[str[16]; 32] = [];
    for w in words(to_lower(text)) {
        if len(w) <= 16 and len(unique) < cap(unique) and not contains(unique, w) {
            push(mut unique, str[16](w));
        }
    }
    sort(mut unique);
    return unique;
}

fn main() {
    let line: str[64] = join(vocabulary("The cat saw the dog"), ", ");
    print(line);
}
```

```
$ bin/alang run vocabulary.al
cat, dog, saw, the
```

## Diagnostics built for agents

```
error[E0215]: a call with `mut` arguments must be its own statement or the whole right-hand side of let/var/assignment/return
 --> oops.al:4:15
  |
4 |         print(pop(mut stack));
  |               ^^^^^^^^^^^^^^
  = hint: store the result first: let value: i32 = pop(mut stack); then use `value`
```

`--json` gives each diagnostic's `code`, `message`, `line`, `column`, `source_line`, `hint` and, where the
change is mechanical, a `"fix"` with the edits (`alang fix --write` applies them). Habits from other
languages (`&&`, `x++`, `Color::Red`, `xs.len()`, `let x = 5`, ...) are answered with the ALang spelling.
See [docs/ERRORS.md](docs/ERRORS.md).

## Tools in an agent host

`alang serve` speaks the [Model Context Protocol](https://modelcontextprotocol.io) over stdio:

```json
{"mcpServers": {"bill": {"command": "/path/to/alang/bin/alang", "args": ["serve", "/path/to/bill.al"]}}}
```

Each `tool fn` is listed with its description, input and output schemas, and read-only, idempotent
annotations; a failing call returns an error result the model can act on. `--watch` serves each new version
that compiles and notifies the host. Up to four calls run at once (`--workers N`, `--max-queued N`), each
within its step budget and `--timeout`, cancellable with `notifications/cancelled`.

`--http` serves MCP's Streamable HTTP transport instead, with sessions, bearer auth (`--token-file`;
without it, loopback only), `/health`, Prometheus `/metrics`, a JSON log line per call, and graceful SIGTERM:

```sh
alang serve bill.al --http 0.0.0.0:8080 --token-file token.txt --workers 8
```

Details: [SPEC.md §10](docs/SPEC.md#10-tools-calling-alang-from-an-agent).

## Embedding in a JVM host

`alang.api` is the jar's one supported API:

```java
import alang.api.*;

Compilation c = Alang.compile(Path.of("bill.al"));
if (!c.ok()) {
    for (Diagnostic d : c.errors()) System.err.println(d + " (" + d.hint() + ")");
    return;
}
Program program = c.program();                            // immutable, thread-safe
String tools = program.toolsJson();                       // tool definitions for the model
CallResult r = program.call("split_bill", "{\"total_cents\": 1000, \"people\": 3}",
    CallOptions.defaults().maxSteps(1_000_000).timeoutMillis(2_000));
r.resultJson();   // [334, 333, 333]; r.toJson() is what `alang call` prints
```

A call never throws for what the model sends: every failure is a `CallResult` with an `errorCode()`.
`Alang.agentsGuide()` returns AGENTS.md for a system prompt.

## Tool results as TOON

[`harness/toon.py`](harness/toon.py) turns `alang call` JSON into [TOON](https://github.com/toon-format/spec)
for a model's prompt; it saves the most on long lists of structs:

```
$ bin/alang call words.al word_counts '{"text": "the cat saw the dog"}' | python3 harness/toon.py
ok: true
result[4]{word,count}:
  the,2
  cat,1
  saw,1
  dog,1
stdout: ""
steps: 113
```

## Documentation

- [AGENTS.md](AGENTS.md): the guide for agents writing ALang (start here)
- [docs/SPEC.md](docs/SPEC.md): the language specification
- [docs/ERRORS.md](docs/ERRORS.md): every diagnostic and trap code (generated)
- [docs/COMPARISON.md](docs/COMPARISON.md): ALang beside other languages
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): C4 diagrams of the toolchain
- [examples/](examples), with [helpers.al](examples/helpers.al) for what the standard library leaves out
- [bench/](bench): do models write more correct tools in ALang or in Python?

Tests: `tests/run/` (golden `.out`, `.trap`, `.in`), `tests/errors/` (expected `.err`), `tests/stack/`
(memory bounds in C), `tests/examples/`.

## Development

```sh
mvn verify                                    # all tests: conformance, docs, determinism, fuzzing, C backend, ArchUnit
mvn test -Dalang.fuzz=3000                    # longer fuzzing
mvn test-compile pitest:mutationCoverage      # mutation testing (weekly in CI)
python3 bench/bench.py selftest
python3 harness/test_toon.py
bin/alang explain markdown > docs/ERRORS.md   # regenerate the error catalog
```

CI runs on Linux, macOS and Windows with gcc and clang. To release, set the version in `pom.xml`, merge to
`main`, and publish a GitHub release with a new tag; [release.yml](.github/workflows/release.yml) re-runs CI,
then attaches the jar, archives, native binaries, AGENTS.md and `SHA256SUMS`, and publishes to GitHub Packages.

## Status

Version 0.6: imports, vendored packages pinned by hash, generics, `Option`/`Result`, function values, tuples, maps, sets, slices, labelled
loops, nested patterns with guards, `if let`, `while let`, `match` and `if` expressions, default parameters and
struct fields, struct update (`P(..p, x: 1)`), UTF-8 text, base-N text (`to_base`, `parse_i64(s, 16)`), bit
functions, wrapping/saturating arithmetic and bit-identical float functions.

## License

MIT, see [LICENSE](LICENSE).
