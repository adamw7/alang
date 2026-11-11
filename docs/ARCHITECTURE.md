# Architecture

[C4 model](https://c4model.com) diagrams of ALang: the [system context](#level-1-system-context), its
[containers](#level-2-containers), the [toolchain's components](#level-3-components-of-the-toolchain) and
[one tool call](#dynamic-view-a-tool-call); level 4 is the code. Each box names its element type, and
technology, in brackets; arrows point from user to used; colors follow the C4 key. The diagrams are
[Mermaid](https://mermaid.js.org) flowcharts, which GitHub renders (Mermaid's own C4 diagrams are
experimental and cross their arrows). `ArchitectureTest` enforces the component diagram's layering, and
`DocsTest` fails when its `[Component: ...]` boxes and the toolchain's packages differ.

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
    host -- "tools, call, serve<br/>[CLI, JSON, MCP]" --> alang
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

The toolchain never reaches out: no network, processes, clock, environment or randomness. It reads the
source file (and stdin for `call -`) and writes stdout, stderr and the `-o` file of `build`. Only the
benchmark talks to the Claude API, and the C compiler is the user's.

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
    host -- "tools, call<br/>[argv or stdin]; serve<br/>[MCP on stdin and stdout]" --> launcher
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

- `bin/alang` runs `mvn package` when `target/alang.jar` is missing or older than `pom.xml`, `grammar/`
  or `src/main`, and starts the jar under `LC_ALL=C.UTF-8`, so arguments decode the same on every host.
- The jar shades in the ANTLR runtime. A compiled program is one C99 file, the program plus the runtime
  [prelude](../src/main/resources/alang/cgen/prelude.c); it writes with `write(2)`, or `AL_WRITE` on
  freestanding targets.
- The [benchmark](../bench/README.md) runs the jar directly, runs Python solutions in separate
  processes, and writes `bench/results/*.jsonl`.

## Level 3: Components of the toolchain

One component per Java package; the generated parser is built from
[`grammar/ALang.g4`](../grammar/ALang.g4) on every build.

```mermaid
flowchart TB
    launcher["<b>bin/alang</b><br/><i>[Container: POSIX shell]</i>"]
    programs[("<b>ALang programs</b><br/><i>[Container: .al files]</i>")]

    subgraph jar ["Toolchain [Container]"]
        cli["<b>CLI</b><br/><i>[Component: alang.cli]</i><br/><br/>Arguments, files and stdin; text or JSON output; exit codes; the MCP server of serve. Frontend runs parse + check"]
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

Most arrows into the bottom two components are left out: every component reports through Diagnostics,
and any may use JSON. The rules behind the arrows, from
[`ArchitectureTest`](../src/test/java/alang/ArchitectureTest.java):

- **Layers**, bottom-up: JSON, Diagnostics, the Model (`alang.ast` and `alang.types` refer to each
  other), then Parser, Interpreter, Checker and C backend, with the CLI on top. Nothing uses the CLI.
- **One value per constant**: the checker evaluates constants with `Interpreter.evalConst`, and the C
  backend turns those values into C initializers; the two do not depend on each other.
- **ANTLR stays in the parser**, and **the AST is written once**: only the parser and the checker set its
  fields, so a checked program can be run, tested and compiled any number of times.
- **I/O at the edge**: only the CLI touches files, stdin or `System.exit`. No component reads a clock,
  randomness or the environment, starts threads, reflects or logs, and no text depends on the host
  locale or charset.

## Dynamic view: a tool call

`alang call prog.al word_counts '{"text": "a b a"}'`, step by step. `run` and `test` take the same path
to the interpreter; `build` hands the checked program to the C backend instead.

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
    CLI-->>Host: {"ok": true, "result": [...], "stdout": "", "steps": 16}
```

Every step is deterministic: the same program and arguments give the same bytes, step count and trap on
every run, machine and locale.
