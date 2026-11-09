# Architecture

These diagrams describe ALang with the [C4 model](https://c4model.com):

1. [System context](#level-1-system-context): who uses ALang and what it talks to.
2. [Containers](#level-2-containers): the pieces that run or hold data.
3. [Components](#level-3-components-of-the-toolchain): the parts of the toolchain.
4. [Dynamic view](#dynamic-view-a-tool-call): one tool call, step by step.

Level 4, the code, is the source itself.

Every box names its element type, and its technology where there is one, in brackets. Arrows point
from the element that uses to the element used, labeled with what for and, in brackets, how. Dashed
boxes are boundaries. Colors follow the usual C4 key: dark blue for people, lighter blues for ALang
and its parts, grey for systems outside it. The diagrams are [Mermaid](https://mermaid.js.org) flowcharts, which
GitHub renders in place; Mermaid's own C4 diagram types are experimental and lay these diagrams out
with crossing arrows.

The component diagram is checked: `ArchitectureTest` enforces the layering it shows, and `DocsTest`
fails when its `[Component: ...]` boxes and the toolchain's packages differ.

## Level 1: System context

```mermaid
flowchart TB
    agent("<b>AI agent</b><br/><i>[Person]</i><br/><br/>Writes ALang programs and fixes them from coded diagnostics")
    host["<b>Agent host</b><br/><i>[Software system]</i><br/><br/>An LLM application that registers tool definitions and runs tool calls"]
    dev("<b>Developer</b><br/><i>[Person]</i><br/><br/>Runs, tests and builds programs; works on the toolchain")
    alang["<b>ALang</b><br/><i>[Software system]</i><br/><br/>Checks, runs, tests and compiles programs; serves tool fns as JSON tools"]
    claude["<b>Claude API</b><br/><i>[Software system]</i><br/><br/>Writes the benchmark's tools"]
    cc["<b>C compiler</b><br/><i>[Software system]</i><br/><br/>gcc or clang"]

    agent -- "Writes .al files; check, test, run<br/>[CLI, --json]" --> alang
    host -- "tools, call<br/>[CLI, JSON]" --> alang
    dev -- "run, test, build<br/>[CLI]" --> alang
    dev -- "Compiles the generated C99" --> cc
    alang -- "Benchmark prompts<br/>[HTTPS]" --> claude

    classDef person fill:#08427b,stroke:#052e56,color:#fff
    classDef system fill:#1168bd,stroke:#0b4884,color:#fff
    classDef external fill:#999999,stroke:#6b6b6b,color:#fff
    class agent,dev person
    class alang system
    class host,claude,cc external
```

The toolchain never reaches out: it has no network access, starts no processes, and reads no clock,
environment or randomness. It reads the source file it is given (and stdin for `call -`) and writes
stdout, stderr and the `-o` file of `build`. The only network traffic is the benchmark's requests to
the Claude API. The C compiler is the user's: `alang build` writes C99, and `cc` turns it into an
executable.

## Level 2: Containers

```mermaid
flowchart TB
    agent("<b>AI agent</b><br/><i>[Person]</i>")
    host["<b>Agent host</b><br/><i>[Software system]</i>"]
    dev("<b>Developer</b><br/><i>[Person]</i>")

    subgraph sys ["ALang [Software system]"]
        launcher["<b>bin/alang</b><br/><i>[Container: POSIX shell]</i><br/><br/>Rebuilds the jar when the sources are newer; runs it with a UTF-8 locale"]
        bench["<b>Benchmark harness</b><br/><i>[Container: Python 3.11, bench/bench.py]</i><br/><br/>Has a model write each task in ALang and in Python, then grades both"]
        toolchain["<b>Toolchain</b><br/><i>[Container: Java 21, target/alang.jar]</i><br/><br/>Parser, checker, reference interpreter and C backend behind one CLI"]
        tasks[("<b>Tasks and results</b><br/><i>[Container: bench/tasks, bench/results]</i><br/><br/>Prompts, signatures, reference solutions, cases; runs as JSONL")]
        programs[("<b>ALang programs</b><br/><i>[Container: .al files]</i><br/><br/>fn main, test blocks, tool fns")]
        native["<b>Compiled program</b><br/><i>[Container: C99 + runtime prelude]</i><br/><br/>Heap-free, stdio-free C that prints the same bytes as the interpreter"]
    end

    claude["<b>Claude API</b><br/><i>[Software system]</i>"]
    cc["<b>C compiler</b><br/><i>[Software system]</i>"]

    agent -- "check, test, run, explain<br/>[CLI, --json]" --> launcher
    host -- "tools, call<br/>[argv or stdin]" --> launcher
    dev -- "run, test, build<br/>[CLI]" --> launcher
    launcher -- "Builds it with Maven if stale;<br/>starts it<br/>[java -jar]" --> toolchain
    bench -- "check, tools, call<br/>[java -jar, subprocess]" --> toolchain
    bench -- "Reads tasks, writes results" --> tasks
    bench -- "Asks for tool code<br/>[Anthropic SDK, HTTPS]" --> claude
    toolchain -- "Reads<br/>[UTF-8]" --> programs
    toolchain -- "Generates<br/>[alang build -o]" --> native
    cc -- "Compiles into an executable" --> native

    classDef person fill:#08427b,stroke:#052e56,color:#fff
    classDef container fill:#438dd5,stroke:#2e6295,color:#fff
    classDef external fill:#999999,stroke:#6b6b6b,color:#fff
    class agent,dev person
    class launcher,bench,toolchain,tasks,programs,native container
    class host,claude,cc external
    style sys fill:none,stroke:#888,stroke-dasharray:6 4
```

| Container | What it is |
|-----------|------------|
| `bin/alang` | The launcher. It runs `mvn package` when `target/alang.jar` is missing or older than `pom.xml`, `grammar/` or `src/main`, and starts the jar under `LC_ALL=C.UTF-8` so command-line arguments decode the same on every host. |
| Toolchain | `target/alang.jar`, a shaded jar with the toolchain and the ANTLR runtime. It implements every `alang` command; its parts are in the component diagram below. |
| ALang programs | `.al` source files: `examples/`, the conformance programs in `tests/`, and whatever an agent writes. |
| Compiled program | The single C99 file `alang build` writes: the program plus the runtime prelude (`src/main/resources/alang/cgen/prelude.c`). It uses no heap and no stdio; output goes to `write(2)`, or to `AL_WRITE` on freestanding targets. |
| Benchmark harness | [`bench/bench.py`](../bench/bench.py) holds one conversation per task and language with a Claude model, grades the programs on hidden cases, and reports silent failures. It runs the jar directly and runs Python solutions in separate processes. See [bench/README.md](../bench/README.md). |
| Tasks and results | `bench/tasks/<name>/` (prompt, signatures, reference solutions, `cases.json`) and the `bench/results/*.jsonl` files that `run` writes. |

## Level 3: Components of the toolchain

One component per Java package; the generated parser is built from
[`grammar/ALang.g4`](../grammar/ALang.g4) on every build.

```mermaid
flowchart TB
    launcher["<b>bin/alang</b><br/><i>[Container: POSIX shell]</i>"]
    programs[("<b>ALang programs</b><br/><i>[Container: .al files]</i>")]

    subgraph jar ["Toolchain [Container]"]
        cli["<b>CLI</b><br/><i>[Component: alang.cli]</i><br/><br/>Arguments, files and stdin; text or JSON output; exit codes. Frontend runs parse + check"]
        parse["<b>Parser</b><br/><i>[Component: alang.parse]</i><br/><br/>Nesting limit, syntax-error hints, parse tree to AST"]
        check["<b>Checker</b><br/><i>[Component: alang.check]</i><br/><br/>Types, names, constants, recursion ban, memory bound"]
        interp["<b>Interpreter</b><br/><i>[Component: alang.interp]</i><br/><br/>Reference semantics, step budget, traps, formatting, JSON mapping"]
        cgen["<b>C backend</b><br/><i>[Component: alang.cgen]</i><br/><br/>Checked AST to one heap-free C99 file with the prelude"]
        gen["<b>Generated parser</b><br/><i>[Component: alang.parser, ANTLR 4]</i><br/><br/>ALangLexer and ALangParser"]
        model["<b>Model</b><br/><i>[Component: alang.ast, alang.types]</i><br/><br/>AST nodes, builtins and types; annotated once by the checker"]
        diag["<b>Diagnostics</b><br/><i>[Component: alang.diag]</i><br/><br/>Source, spans, diagnostics and the E, W and R code catalog"]
        util["<b>JSON</b><br/><i>[Component: alang.util]</i><br/><br/>Deterministic JSON reader and writer"]
    end

    launcher -- "Starts<br/>[java -jar]" --> cli
    cli -- "Reads<br/>[UTF-8]" --> programs
    cli -- "Parser.parse" --> parse
    cli -- "Checker.check" --> check
    cli -- "Runs main, tests, tools" --> interp
    cli -- "CGen.generate" --> cgen
    parse -- "Lexes and parses" --> gen
    parse -- "Builds the AST" --> model
    check -- "Annotates the AST" --> model
    check -- "Evaluates constants" --> interp
    cgen -- "Constant values" --> interp
    cgen -- "Reads" --> model
    interp -- "Reads" --> model
    model -- "Spans" --> diag
    diag -- "JSON output" --> util

    classDef container fill:#438dd5,stroke:#2e6295,color:#fff
    classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
    class launcher,programs container
    class cli,parse,check,interp,cgen,gen,model,diag,util component
    style jar fill:none,stroke:#888,stroke-dasharray:6 4
```

The diagram leaves out most arrows into the two bottom components: every component reports through
Diagnostics (syntax and type errors, trap codes), and any of them may use JSON. The rules behind the
arrows come from [`ArchitectureTest`](../src/test/java/alang/ArchitectureTest.java):

- **Layers, read bottom-up**: JSON (`alang.util`), Diagnostics (`alang.diag`), the Model
  (`alang.ast` and `alang.types`, which refer to each other), then Parser, Interpreter, Checker and
  C backend, with the CLI on top. A component uses only the layers below it, and nothing uses the CLI.
- **The checker and the C backend share the interpreter's values.** The checker evaluates constants
  with `Interpreter.evalConst`, so a constant has one value everywhere; the C backend turns those
  values (`alang.interp.Values`) into C initializers. The two do not depend on each other.
- **ANTLR stays in the parser.** Only `alang.parse` sees the generated parser or the ANTLR runtime;
  everything after it works on the AST.
- **The AST is written once.** Only the parser and the checker set fields on AST nodes. The
  interpreter, C backend and CLI read the checker's annotations, so one checked program can be run,
  tested and compiled any number of times with the same result.
- **I/O at the edge.** Only the CLI touches files, stdin or `System.exit`; the other components get
  source text and output streams from it. No component reads a clock, randomness or the
  environment, starts threads, uses reflection or logs, and all text is independent of the host
  locale and charset.

## Dynamic view: a tool call

What happens for `alang call prog.al word_counts '{"text": "a b a"}'`, drawn as a sequence. `run`
and `test` take the same path up to the interpreter; `build` hands the checked program to the C
backend instead.

```mermaid
sequenceDiagram
    autonumber
    actor Host as Agent host
    participant CLI as CLI<br/>(alang.cli)
    participant Parse as Parser<br/>(alang.parse)
    participant Check as Checker<br/>(alang.check)
    participant Interp as Interpreter<br/>(alang.interp)

    Host->>CLI: alang call prog.al word_counts '{"text": "a b a"}'
    CLI->>CLI: read prog.al as UTF-8
    CLI->>Parse: Parser.parse
    Parse-->>CLI: AST, or syntax diagnostics
    CLI->>Check: Checker.check
    Check-->>CLI: annotated AST and memory report, or diagnostics
    Note over CLI: compile errors end the call:<br/>{"ok": false, "error": {"code": "compile_error"}}
    CLI->>CLI: find the tool fn, parse the JSON arguments
    CLI->>Interp: JsonValues.fromJson for each parameter
    Interp-->>CLI: ALang values, or bad_arguments
    CLI->>Interp: Interpreter.call, with the step budget
    Interp-->>CLI: result and captured print output, or a trap (R code)
    CLI->>Interp: JsonValues.toJson(result)
    Interp-->>CLI: JSON value, or bad_result (NaN, infinity)
    CLI-->>Host: {"ok": true, "result": [...], "stdout": ""}
```

Every step is deterministic: the same program and arguments give the same bytes, the same step
count and the same trap on every run, machine and locale.
