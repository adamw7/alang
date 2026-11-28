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

## Get started

```sh
curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh   # Linux, macOS; details below
cd ~/.alang/examples
alang run hello.al
alang run expenses.al < expenses.in      # sum a CSV per category
alang test expenses.al
alang call expenses.al summarize '{"csv": "date,category,amount\n2026-01-02,food,12.50"}'
echo '{"jsonrpc": "2.0", "id": 1, "method": "skills/list"}' | alang serve expenses.al --skills skills
                                         # MCP: the tool, and a skill that says how to use it
```

Then hand your agent [AGENTS.md](AGENTS.md) (installed as `~/.alang/AGENTS.md`) and ask it for a tool; serve
what it writes to any MCP client with `alang serve prog.al`, and the [skills](#skills-instructions-that-ship-with-the-tools)
that say how to use it with `--skills` ([how](#tools-in-an-agent-host)).

## Why

| Property | How |
|----------|-----|
| **Easy for agents** | One way to do things, explicit types, no precedence puzzles, no shadowing. Every diagnostic has a stable code, location and fix hint, also as JSON. Built-in `test` blocks and `requires`/`ensures` contracts. |
| **No heap** | Every type has a compile-time size; no pointers, `null` or allocator. `alang check` bounds each entry point's worst-case memory (8 MiB budget by default). |
| **Deterministic** | No clock, randomness, environment, threads or I/O but `print`; input arrives whole, and outside data only as the replies to `host fn` calls, which are recorded and can be replayed. No undefined behavior: overflow, bad indexes and conversions trap with fixed codes. Floats are correctly rounded, `sin`/`exp`/`pow` included. Tests compare the C backend byte for byte with the interpreter under gcc and clang, `-O0` to `-O3`, and across locales. |
| **Always terminates** | No recursion; every `while` states a `limit`. |
| **Safe to run** | Tools take JSON in and give JSON out, within a deterministic step budget (backed by `--timeout`); they reach nothing but the `host fn`s their host chooses to answer. |
| **Ships its own instructions** | `alang serve --skills` serves [Agent Skills](https://agentskills.io) beside the tools over MCP, so the agent that calls them also learns when and how to. |
| **Runs anywhere** | A JVM interpreter and a C99 backend emitting heap-free, stdio-free C. |

[docs/COMPARISON.md](docs/COMPARISON.md) compares these with Python, JavaScript, Go, Rust, C and Java.

## Install

```sh
pip install alang-toolchain                                                      # any OS with Python 3.9+
curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh   # Linux, macOS
```

`pip` installs the `alang` command and a Python module ([below](#embedding-in-a-python-host)). Linux x64/arm64
(glibc 2.35+), Apple-silicon macOS and Windows x64 get the native binary; elsewhere the jar, which needs Java
21+ (`pip install "alang-toolchain[java]"` brings a Java runtime too).

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
| `call FILE TOOL [JSON \| -] [--timeout S]` | Call a tool; `-` reads the arguments from stdin; `--hosts FILE` or `--replay FILE` answers its host fns |
| `fuzz FILE` | Call every tool, and a `main` that reads input, with arguments at their limits |
| `serve FILE [--watch] [--http ADDR] [--skills DIR]` | Serve every tool, and the skills in DIR, over MCP |
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
within its step budget and `--timeout`, cancellable with `notifications/cancelled`. A client that takes MCP
tasks (Claude Code does) gets a task for a call that runs longer than a second, and keeps working while it runs.

`--http` serves MCP's Streamable HTTP transport instead, with sessions, bearer auth (`--token-file`;
without it, loopback only), HTTPS with TLS 1.3 only (`--tls-cert`, `--tls-key`: PEM), `/health`, Prometheus
`/metrics`, a JSON log line per call, and graceful SIGTERM:

```sh
alang serve bill.al --http 0.0.0.0:8443 --token-file token.txt --tls-cert fullchain.pem --tls-key key.pem --workers 8
```

Details: [SPEC.md §10](docs/SPEC.md#10-tools-calling-alang-from-an-agent).

### Skills: instructions that ship with the tools

A tool's description says what one call does; a skill says how the tools go together for a task: which to
call, in what order, and what to do with the result. `alang serve --skills DIR` serves the
[Agent Skills](https://agentskills.io) in DIR beside the tools, by MCP's
[Skills extension](https://skills.extensions.modelcontextprotocol.io/) (`io.modelcontextprotocol/skills`),
so whoever installs the server gets both. A skill is a directory with a `SKILL.md`, whose YAML frontmatter
gives its `name` (the directory's name) and a `description` that says when to use it, and any files it refers
to. [examples/skills/summarizing-expenses](examples/skills/summarizing-expenses/SKILL.md) is one for
`expenses.al`:

```text
examples/skills/summarizing-expenses/
  SKILL.md               ---
                         name: summarizing-expenses
                         description: "Sum a CSV export of expenses per category with the summarize tool..."
                         ---
                         # Summarizing expenses
                         1. Get the rows into the CSV `summarize` takes: ...
  examples/request.md    a request and its result, which SKILL.md links to
```

```json
{"mcpServers": {"expenses": {"command": "/path/to/alang/bin/alang",
  "args": ["serve", "/path/to/expenses.al", "--skills", "/path/to/skills"]}}}
```

- `skills/list` gives each skill's frontmatter and every file with its SHA-256 and size; `skills/get` gives one.
  Its files are resources at `skill://summarizing-expenses/SKILL.md`, `.../examples/request.md`, read with
  `resources/read` and browsed with `resources/directory/read`. The server's instructions name the skills, so
  an agent whose client does not know the extension can still read them as resources.
- DIR may be one skill or a tree of them: `DIR/billing/refunds/SKILL.md` is `skill://billing/refunds/SKILL.md`.
  `--skills` may be given more than once.
- Every file is read and checked at start, so a skill a client would refuse stops the server with the reason
  and the line: a `name` that is not its directory's, no `description`, more than 512 files or 16 MiB, a
  symbolic link. The frontmatter is sent as JSON, verbatim, so values that YAML parsers read differently
  must be quoted: `yes`, `010`, `2024-01-01`, and text holding `: ` (`description: "Use when: ..."`).
- `--watch` serves each change to the skills too, and tells the client.

## Reaching outside data

A `host fn` has no body: the host answers each call, with JSON. It is how a tool gets a price list, a database
row or a web page, while the rest of the program stays deterministic: every question and answer is recorded,
a test binds the host fn to a function of its own, and `fuzz` answers with values at their limits.

```alang
/// The price of an item in cents, or Option.None when it is not sold.
host fn price(sku: str[16]) -> Option<i64>
    ensures unwrap_or(result, 0) >= 0;

/// The total of the items that are sold.
tool fn total(skus: list[str[16]; 8]) -> i64 {
    var sum: i64 = 0;
    for s in skus {
        sum += unwrap_or(price(s), 0);
    }
    return sum;
}

fn fixed_prices(sku: str[16]) -> Option<i64> {
    return if sku == "tea" then Option.Some(350) else Option.None;
}

test "adds up the sold items" with price = fixed_prices {
    assert total(["tea", "gold", "tea"]) == 700;
}
```

```
$ cat hosts.json
{"price": {"command": ["python3", "price.py"]}}
$ alang call shop.al total '{"skus": ["tea", "gold"]}' --hosts hosts.json | tee report.json
{"ok": true, "result": 350, "host_calls": [{"fn": "price", "args": {"sku": "tea"}, "result": 350}, {"fn": "price", "args": {"sku": "gold"}, "result": null}], "stdout": "", "steps": 9}
$ alang call shop.al total '{"skus": ["tea", "gold"]}' --replay report.json   # the same report, with no host
```

Each call runs the command with `{"fn": "price", "args": {"sku": "tea"}}` on stdin and reads the JSON result
from stdout; `alang serve --hosts` does the same for an MCP client, which is told that such a tool reaches
outside data. Embedders pass a `Host` (`CallOptions.host`) or link `al_host` into the C build. See
[examples/orders.al](examples/orders.al) and [SPEC.md §10.1](docs/SPEC.md#101-host-functions).

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

## Embedding in a Python host

`pip install alang-toolchain` adds the `alang` module, which runs the toolchain in a subprocess:

```python
import alang

report = alang.check("bill.al")                   # `alang check --json` as a dict
if not report["ok"]:
    for d in report["diagnostics"]:
        print(d["code"], d["line"], d["message"], d["hint"])
tools = alang.tools("bill.al")                    # tool definitions for the model
r = alang.call("bill.al", "split_bill", {"total_cents": 1000, "people": 3})
r["result"]       # [334, 333, 333]; r is what `alang call` prints, parsed
alang.agents_md()                                 # AGENTS.md for a system prompt
alang.invoke("test", "bill.al")                   # any command, as a subprocess.CompletedProcess
```

Like `alang call`, `alang.call` returns failures as a reply with `"ok": False` and an `error` code; it
raises `alang.AlangError` only when the toolchain cannot start. [python/](python) builds the wheels.

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

**Dependencies:** do not introduce Spring (or any other application framework) into the compiler or its
tooling; keep the Java code plain and the dependency list short.

## Status

Version 0.7: host fns for outside data, with recorded and replayable calls; `Option` as `null` or its value in
JSON. Imports, vendored packages pinned by hash, generics, `Option`/`Result`, function values, tuples, maps, sets, slices, labelled
loops, nested patterns with guards, `if let`, `while let`, `let ... else`, patterns in `for`, the `?` operator on
`Option`/`Result`, `match` and `if` expressions, default parameters and
struct fields, struct update (`P(..p, x: 1)`), UTF-8 text, base-N text (`to_base`, `parse_i64(s, 16)`), bit
functions, wrapping/saturating arithmetic and bit-identical float functions.

## License

MIT, see [LICENSE](LICENSE).
