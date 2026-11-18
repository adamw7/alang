# ALang

[![CI](https://github.com/adamw7/alang/actions/workflows/ci.yml/badge.svg)](https://github.com/adamw7/alang/actions/workflows/ci.yml)

Agentic language. For agents. Not for humans. Does not use heaps and other source of problems.

**An agent-first programming language: no heap, fully deterministic, always terminates.** A small,
statically typed language that AI agents write and hosts run safely. The parser is generated from one
ANTLR 4 grammar, [`grammar/ALang.g4`](grammar/ALang.g4), on every build.

**Demo:** [docs/demo/demo.mp4](docs/demo/demo.mp4) (under 2 minutes, narrated, with English subtitles)
shows Claude Code building a small ALang tool: write, `alang check`, fix from the hints, test, run and
call it. Every `alang` output in it is real; [`docs/demo/make_demo.js`](docs/demo/make_demo.js) re-runs
the commands and renders it again.

**Why ALang:** [docs/demo/why.mp4](docs/demo/why.mp4) (under 2 minutes, narrated, with English subtitles)
sets six bugs that JavaScript, C and Python let through (a string plus a number, an overflowing midpoint,
a missing null check, a loop that never ends, a buffer overflow, a tool called with bad input) beside the
same programs in ALang. Every program in it really runs; [`docs/demo/make_why.js`](docs/demo/make_why.js)
re-runs them and renders it again.

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

Install it with `curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh` (see
[Install](#install)), then give your agent [AGENTS.md](AGENTS.md).

## Why

| Property | How ALang guarantees it |
|----------|-------------------------|
| **Easy for agents** | One way to do things, explicit types, Rust-like syntax, no precedence puzzles, no shadowing. Every diagnostic has a stable code, an exact location and a fix hint, also as JSON. Built-in `test` blocks and `requires`/`ensures` contracts. |
| **No heap** | Every type has a compile-time size (`str[N]`, `list[T; N]`, `[T; N]`, structs, enums); no pointers, `null` or allocator. `alang check` bounds the worst-case memory of every entry point and rejects a program over its budget (8 MiB by default). |
| **Deterministic** | No clock, randomness, environment, threads or I/O except `print`; input arrives whole, as a tool's JSON arguments or the stdin of `fn main`. No undefined behavior: overflow, bad indexes and bad conversions trap with fixed codes. Fixed evaluation order, floats from correctly rounded operations only (`sin`, `exp` and `pow` included, which ALang computes itself), exact formatting. The tests run every program repeatedly and under several locales, and compare the C backend byte for byte with the interpreter under gcc and clang at `-O0` to `-O3`. |
| **Always terminates** | Recursion is a compile error; every `while` loop states an iteration `limit`. |
| **Safe to run agent code** | Tool functions take JSON in and return JSON out with no access to the host. A deterministic step budget bounds runtime: it counts the data each statement goes through, not only the statements, so a step takes about as long whatever a tool does. `--timeout` backs it with a wall-clock limit. |
| **Runs anywhere** | A reference interpreter (JVM) and a C99 backend that emits heap-free, stdio-free C with byte-identical behavior. |

[docs/COMPARISON.md](docs/COMPARISON.md) sets these guarantees beside Python, JavaScript, Go, Rust, C
and Java, with programs you can run.

## Install

On Linux or macOS:

```sh
curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh
```

It installs the toolchain in `~/.alang` and links `~/.local/bin/alang` to it; `ALANG_HOME`, `ALANG_BIN_DIR` and
`ALANG_VERSION` choose another place or release ([install.sh](install.sh) says how). On Linux (x64 and arm64,
glibc 2.35 or newer) and on macOS with Apple silicon it installs a native binary, which needs no Java and starts
in milliseconds; elsewhere it installs the jar's archive below, which needs Java 21 or newer. It checks every
download against the release's `SHA256SUMS`. Releases after 0.6.0 carry the native binaries, also on their own:
`alang-linux-x64.tar.gz`, `alang-linux-arm64.tar.gz`, `alang-macos-arm64.tar.gz` and, for Windows,
`alang-windows-x64.zip` with `bin\alang.exe`.

Or download `alang-VERSION.tar.gz` (or `.zip`) from the [latest release](https://github.com/adamw7/alang/releases/latest)
and unpack it; it needs only Java 21 or newer. It holds `bin/alang` (put it, or a link to it, on your
`PATH`), `lib/alang.jar`, [AGENTS.md](AGENTS.md), the docs and the examples, so the commands below work in
it as they do in a clone. On Windows, `bin\alang.cmd` runs it from cmd.exe or PowerShell. Both shells can strip
the quotes out of a JSON argument, so pass `alang call` arguments on stdin there:
`alang call prog.al tool - < args.json` in cmd.exe, `Get-Content args.json | alang call prog.al tool -` in
PowerShell. A release also has the runnable jar on its own (`java -jar alang-VERSION.jar`),
and the guide for agents at an address that does not change:
<https://github.com/adamw7/alang/releases/latest/download/AGENTS.md>.

For a JVM host, the jar is in GitHub Packages as `io.github.adamw7:alang` (repository
`https://maven.pkg.github.com/adamw7/alang`; GitHub asks for a token even to read it), with the ANTLR
runtime inside and the guide on the classpath at `/alang/AGENTS.md`, ready for a system prompt. Its API is
in [Embedding in a JVM host](#embedding-in-a-jvm-host).

## Quick start

In a clone, `bin/alang` needs JDK 21+ and Maven and builds the jar itself; from a release it needs only
Java. A C compiler is optional (for `alang build`).

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
| `alang run FILE [--json] [--max-steps N] [--max-stack B]` | Run `fn main()`; a `fn main(input: str[N])` gets all of stdin (`alang run prog.al < data.txt`) |
| `alang test FILE [--json] [--filter TEXT] [--max-steps N] [--max-stack B]` | Run all `test "..." { }` blocks (one written `test "..." traps R0006 { }` passes only if it stops with that trap); a filter that matches no test fails |
| `alang tools FILE [--json] [--max-steps N] [--max-stack B]` | JSON tool definitions for every `tool fn` (name, description, input_schema, and input_examples from the tests' calls) |
| `alang call FILE TOOL [JSON \| -] [--max-steps N] [--max-stack B] [--timeout S]` | Call a tool with JSON arguments; `-` reads them from stdin, none means `{}`; a call that runs longer than `--timeout` seconds (60, 0 for none) stops with the error `timeout` |
| `alang fuzz FILE [--json] [--max-steps N] [--max-stack B]` | Call every `tool fn`, and a `fn main(input: str[N])`, with arguments at their limits (empty and full strs of many shapes, full lists, the smallest and largest numbers); report each trap with the arguments that hit it |
| `alang serve FILE [--max-steps N] [--max-stack B] [--timeout S] [--watch]` | Serve every `tool fn` to an agent host over MCP (JSON-RPC on stdin and stdout), up to four calls at a time, each within the step budget and `--timeout`; `--watch` serves each new version of the files that compiles |
| `alang build FILE [-o out.c] [--max-stack B]` | Compile to heap-free C99 |
| `alang fix FILE [--write] [--json] [--max-stack B]` | Apply the fix of every diagnostic that has one (marked `= fix:`, `"fix"` in JSON), checking again after each round, since a fixed syntax error lets the parser reach the next; print the result, or `--write` it; fails while errors without a fix remain |
| `alang fmt FILE [--write \| --check]` | Print the file laid out the one way (indentation, spaces between tokens, blank lines; the lines you broke stay broken); `--write` rewrites it, `--check` fails if it is not formatted |
| `alang debug FILE [JSON] [--break LOC]... [--test NAME \| --call TOOL] [--input F] [--json] [--max-stops N]` | Run `fn main`, a test or a tool call under a debugger that reads commands from stdin: breakpoints on a line, `file:line` or function, with an optional `if` condition, `step`/`next`/`finish`, `where`, `locals`, `print` of any expression; it also stops where a trap happens, before the stack unwinds. Once the commands run out, each later stop is printed with its variables and passed ([SPEC.md §13](docs/SPEC.md#13-the-debugger)) |
| `alang explain CODE \| --all \| --markdown` | Explain a diagnostic or trap code, or all of them |

Exit codes: `0` success, `1` errors, traps (also those `fuzz` finds) or failing tests, `2` usage errors (including an option the
command does not take, or an extra argument). With `--json`, and always for `call`, errors are JSON on
stdout too; `call`'s error codes are `usage_error`, `compile_error`, `bad_tool`, `bad_json`,
`bad_arguments`, `bad_result`, `timeout` and the trap codes (`R....`).

## Standard library

Without type parameters, the standard library is a set of builtins that work on any type and capacity, in the
interpreter and the C backend alike, traps included
([SPEC.md §9](docs/SPEC.md#9-builtins-and-the-standard-library)):

| Area | Functions |
|------|-----------|
| Core | `print` `len` `cap` `push` `pop` `clear` `append` `panic` |
| Math | `abs` `min` `max` (of two numbers, or of a list) `sum` `clamp` `pow` `gcd` `lcm` `wrapping_add` `wrapping_sub` `wrapping_mul` `saturating_add` `saturating_sub` `saturating_mul` `checked_add` `checked_sub` `checked_mul` `rem_euclid` `div_euclid` `mul_mod` `sqrt` `floor` `ceil` `round` `trunc` `exp` `log` `log2` `log10` `sin` `cos` `tan` `asin` `acos` `atan` `atan2` `is_nan` `is_finite` |
| Characters | `is_digit` `is_alpha` `is_alnum` `is_space` `is_upper` `is_lower` `to_upper` `to_lower` |
| Text | `contains` `index_of` `last_index_of` `count` `starts_with` `ends_with` `slice` `trim` `split` `words` `try_split` `try_words` `join` `replace` `is_int` `is_float` `parse_i64` `parse_f64` `fixed` `pad_left` `pad_right`, and `i64(s)`, `f64(s)` to parse; UTF-8 code points: `is_utf8` `char_count` `chars` `push_char` `slice_chars` |
| Collections | `sort` `sort_by` `reverse` `dedup` `binary_search` `insert` `remove` `retain`; `any` `all` `count_if` `find_index` with a function passed by name (and `contains`, `index_of`, `last_index_of`, `count`, `slice` on lists and arrays); `map[K, V; N]` and `set[T; N]` with `m[k]`, `get_or`, `insert`, `remove`, `contains` |

A user function takes any capacity with a `_` in a parameter's type: `fn total(xs: list[i64; _])`.

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

`split`, `words`, `join` and `replace` take their capacities from the variable they are stored in, and
`sort` orders every type, structs field by field, so a list of records sorts by putting the key first.

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
has `severity`, `code`, `title`, `message`, `line`, `column`, `length`, `source_line` and `hint`. Where the
hint's change is mechanical, a `"fix"` lists the edits that make it (`line`, `column`, `length` in code
points, and the `replacement`), and the text shows it as `= fix:`: `&&` becomes `and`, `xs.len()` becomes
`len(xs)`, a missing `;` or `mut` is inserted, `let n = len(xs);` gets its type (`let n: i64`), and a call
with `mut` arguments inside a condition or an expression moves to a `let` of its own on the line above.
`alang fix prog.al --write` applies them all. A trap
gives its `line`, `column` and `source_line` the same way, and a diagnostic or trap in an imported file
adds its `file`. Habits from other languages (`&&`, `x++`, `Color::Red`, `Point { x: 1 }`, `xs.len()`,
`let x = 5`, `while` without a limit, `null`, `use`/`#include`, `substr`, `parse_int`, `sorted`, ...) are answered with the ALang spelling.
See [docs/ERRORS.md](docs/ERRORS.md).

## Tools in an agent host

`alang serve` speaks the [Model Context Protocol](https://modelcontextprotocol.io) over stdin and stdout,
so any MCP host can use a program's tools without code of its own. In a host's server list (Claude Code's
`.mcp.json`, for example):

```json
{"mcpServers": {"bill": {"command": "/path/to/alang/bin/alang", "args": ["serve", "/path/to/bill.al"]}}}
```

The host sees each `tool fn` with the description and input schema `alang tools` prints, an output schema
for its result, and annotations that hold for every ALang tool: read-only, not destructive, idempotent,
closed-world. A call's text is the JSON `alang call` prints, and a call that fails, from bad arguments to
a trap, comes back as an error result the model can read and fix. The program is checked once, at start:
restart the server after editing it, or start it with `--watch`. Then it serves each version of the program's
files that compiles, a moment after it is saved, and tells the host when the tools change
(`notifications/tools/list_changed`); a version with errors is reported on stderr, and the one before it
stays served.

Up to four calls run at once (`--workers N`), so a slow one holds up neither the others nor `ping` and
`tools/list`; up to 256 more wait (`--max-queued N`), and one beyond that is refused at once as busy. Each
stops at its step budget, or after `--timeout` seconds (60 by default), with an error result, and the
host can stop one early with `notifications/cancelled`. When stdin ends, the server finishes the calls it
has started and exits.

### Over HTTP

For a host that reaches the tools over the network, `--http` serves MCP's Streamable HTTP transport instead
of stdio:

```sh
head -c 32 /dev/urandom | base64 > token.txt
alang serve bill.al --http 0.0.0.0:8080 --token-file token.txt --workers 8
```

```json
{"mcpServers": {"bill": {"type": "http", "url": "http://tools.internal:8080/mcp",
  "headers": {"Authorization": "Bearer <the token>"}}}}
```

- **Sessions**: `initialize` returns an `Mcp-Session-Id`; each client's request ids and cancellations are its
  own, and `DELETE /mcp` ends a session. Requests are answered with plain JSON; the server sends no messages
  of its own, so a client lists the tools again rather than waiting for `list_changed`.
- **Auth**: with `--token-file`, every request but `/health` needs `Authorization: Bearer <token>`. Without
  one, the server only listens on a loopback address. Requests from a browser `Origin` are refused unless
  `--allow-origin` names it.
- **Health and metrics**: `GET /health` for a load balancer, `GET /metrics` in the Prometheus format (calls
  by tool and outcome, steps, time, calls in flight, sessions, HTTP status codes).
- **Logs**: one JSON line per request and per call on stderr, with the session, tool, outcome, steps and
  milliseconds.
- **Shutdown**: SIGTERM stops taking requests and finishes the calls under way.

[SPEC.md §10](docs/SPEC.md#10-tools-calling-alang-from-an-agent) has the details.

## Embedding in a JVM host

A Java (or Kotlin, Scala, ...) host can compile and call ALang tools in-process through `alang.api`, the
jar's one supported API; every other package is internal and can change in any release.

```java
import alang.api.*;

Compilation c = Alang.compile(Path.of("bill.al"));       // or Alang.compile("bill.al", text)
if (!c.ok()) {
    for (Diagnostic d : c.errors()) System.err.println(d + " (" + d.hint() + ")");
    return;
}
Program program = c.program();                            // immutable: share it between threads
String tools = program.toolsJson();                       // Messages API tool definitions for the model

CallResult r = program.call("split_bill", "{\"total_cents\": 1000, \"people\": 3}",
    CallOptions.defaults().maxSteps(1_000_000).timeoutMillis(2_000));
r.ok();           // true
r.resultJson();   // [334, 333, 333]
r.toJson();       // the report `alang call` prints: hand it back as the tool result
```

A call never throws for what the model sends: an unknown tool, bad JSON, arguments that do not fit, a trap,
the step budget, the time limit and a cancellation (`CallOptions.cancellation(Cancellation)`) all come back as
a `CallResult` with an `errorCode()`. `Alang.agentsGuide()` returns AGENTS.md for the model's system prompt,
and `CompileOptions.maxStackBytes` sets the memory budget.

## Tool results in a prompt

`alang call` prints JSON, and a harness should keep reading `ok`, `result` and `error.code` from it. For
the copy it shows a model, [`harness/toon.py`](harness/toon.py) turns the JSON into
[TOON](https://github.com/toon-format/spec): the same data with indentation instead of braces, less
quoting, and a list of structs as a table that names the fields once.

```
$ bin/alang call words.al word_counts '{"text": "the cat saw the dog"}'
{"ok": true, "result": [{"word": "the", "count": 2}, {"word": "cat", "count": 1}, {"word": "saw", "count": 1}, {"word": "dog", "count": 1}], "stdout": "", "steps": 113}
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
- [examples/helpers.al](examples/helpers.al): tested helpers the standard library leaves out
  (pseudo-random numbers, a map in insertion order), for lists and strs of any capacity, to import
  (`import "helpers.al";`, as [examples/tally.al](examples/tally.al) does) or copy into a program

`src/main/java/alang/` holds the toolchain, one package per component (see the architecture);
`tests/run/` has programs with golden output (`.out`), expected traps (`.trap`) and standard input
(`.in`), and the files they import in `tests/run/lib/`; `tests/errors/` programs that must fail with given
codes and exact messages (`.err`); `tests/stack/` programs that must fit their memory bound when compiled
to C; and `tests/examples/` the examples' golden output.

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
Linux, macOS and Windows, smoke-tests `bin/alang` against the packaged jar and the release archives' launchers
against theirs (`alang.cmd` from PowerShell on Windows), and runs the TOON converter's tests,
the benchmark self-test and a 3000-mutation fuzzing campaign. Every test, CI step and job has a time limit, so a hang fails fast.
Mutation testing is too slow for every push: it runs every Saturday on `main`
([`.github/workflows/mutation.yml`](.github/workflows/mutation.yml), or on demand from the Actions tab),
uploads the `pit-report` artifact, and fails when the mutation score drops below 74% or the test
strength below 85%; [`pom.xml`](pom.xml) explains what is mutated and why. Renovate keeps the Maven
dependencies, plugins and GitHub Actions current and merges its pull requests once CI passes;
mutation testing is not part of that, so a PIT update is first exercised by the next Saturday run.

### Releases

`mvn package` builds what a release publishes: `target/alang.jar`, and `target/alang-VERSION.tar.gz` and
`.zip` with the jar, launchers for sh and Windows that need only Java, AGENTS.md, the docs, the grammar and the examples
([`src/dist/assembly.xml`](src/dist/assembly.xml)). The jar carries AGENTS.md, the license and the
ANTLR runtime's notice ([THIRD-PARTY-NOTICES](THIRD-PARTY-NOTICES)). To release, set the new version in
`pom.xml` (the build hands it to `Main.VERSION`), merge to `main`, then draft a release on GitHub with a
new tag such as `0.3.0` on that commit, and publish it.

[`.github/workflows/release.yml`](.github/workflows/release.yml) then runs the whole CI on the tagged
commit, and only then publishes: it attaches `alang-VERSION.jar`, both archives, AGENTS.md and
`SHA256SUMS` to the release, puts a table of them above its notes, and puts the jar, its pom and the
archives in GitHub Packages. It creates no tag and no release. A failed run can be re-run: GitHub
Packages takes a version only once, so a version already there is left as it is, and the files are
attached again. For a release whose tag predates this workflow, run the Release workflow from the
Actions tab with its tag.

## Status

Version 0.6. A program can span several files with `import "file.al";`, or keep each file's names
apart with `import "file.al" as name;` ([SPEC.md §3.1](docs/SPEC.md#31-imports)). Functions, structs and enums
take type parameters, every program has `Option<T>` and `Result<T, E>`, functions take function values (no
closures), and the language has tuples, maps and sets, slices,
labelled loops, nested patterns (or-patterns, ranges, tuples, structs) with guards, `if let`, `match` and
`if ... then ... else` expressions, default parameter values, tests that expect a trap, UTF-8 text functions,
wrapping and saturating arithmetic, and the elementary float functions, bit-identical on every backend.

The toolchain checks, tests, formats (`alang fmt`) and fixes (`alang fix`) programs, fuzzes every tool at
its limits (`alang fuzz`), compiles to heap-free C99 (`alang build`), and serves tools over MCP
(`alang serve`, over stdio or HTTP, with `--watch` to pick up each version that compiles), and a JVM host
can do the same in-process through the `alang.api` package. Every call runs within a
deterministic step budget that bounds its time. [examples/helpers.al](examples/helpers.al) adds tested
helpers the standard library leaves out, such as a map that keeps its keys in insertion order.

## License

MIT, see [LICENSE](LICENSE).
