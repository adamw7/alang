# Architecture

[C4](https://c4model.com) diagrams of ALang as Mermaid flowcharts. `ArchitectureTest` enforces the
component layering; `DocsTest` fails when the `[Component: ...]` boxes and the packages differ.

## Containers

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

The toolchain never reaches out: no network, processes, clock, environment or randomness. It reads the
source and its imports (and stdin for `call -` and `fn main(input)`), and writes stdout, stderr and
`build -o`. `bin/alang` rebuilds the jar when sources are newer and runs it under `LC_ALL=C.UTF-8`. A
compiled program is one C99 file: the program plus the [prelude](../src/main/resources/alang/cgen/prelude.c),
writing with `write(2)` (`_write` on Windows, `AL_WRITE` freestanding). Only the
[benchmark](../bench/README.md) talks to the Claude API.

## Components of the toolchain

One component per Java package; the parser is generated from [`grammar/ALang.g4`](../grammar/ALang.g4).

```mermaid
flowchart TB
    launcher["<b>bin/alang</b><br/><i>[Container: POSIX shell]</i>"]
    programs[("<b>ALang programs</b><br/><i>[Container: .al files]</i>")]

    subgraph jar ["Toolchain [Container]"]
        api["<b>Embedding API</b><br/><i>[Component: alang.api]</i><br/><br/>The supported Java API: compile, diagnostics, tool definitions, calls with a step budget, time limit and cancellation"]
        cli["<b>CLI</b><br/><i>[Component: alang.cli]</i><br/><br/>Arguments, files and stdin; text or JSON output; exit codes; the MCP server of serve, over stdio or HTTP. Frontend reads the imported files, runs parse + check"]
        parse["<b>Parser</b><br/><i>[Component: alang.parse]</i><br/><br/>Nesting limit, syntax-error hints, parse tree to AST"]
        check["<b>Checker</b><br/><i>[Component: alang.check]</i><br/><br/>Types, names, constants, recursion ban, memory bound"]
        interp["<b>Interpreter</b><br/><i>[Component: alang.interp]</i><br/><br/>Reference semantics, the standard library, step budget, traps, formatting, JSON mapping"]
        cgen["<b>C backend</b><br/><i>[Component: alang.cgen]</i><br/><br/>Checked AST to one heap-free C99 file with the prelude"]
        gen["<b>Generated parser</b><br/><i>[Component: alang.parser, ANTLR 4]</i><br/><br/>ALangLexer and ALangParser"]
        model["<b>Model</b><br/><i>[Component: alang.ast, alang.types]</i><br/><br/>AST nodes, builtins and types; annotated once by the checker"]
        diag["<b>Diagnostics</b><br/><i>[Component: alang.diag]</i><br/><br/>Source, spans, diagnostics and the E, W and R code catalog"]
        util["<b>JSON</b><br/><i>[Component: alang.util]</i><br/><br/>Deterministic JSON reader and writer"]
    end

    launcher -- "Starts<br/>[java -jar]" --> cli
    api -- "Frontend.compile, Main.runTool" --> cli
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
    class api,cli,parse,check,interp,cgen,gen,model,diag,util component
    style jar fill:none,stroke:#888,stroke-dasharray:6 4
```

Every component reports through Diagnostics and may use JSON. The rules, from
[`ArchitectureTest`](../src/test/java/alang/ArchitectureTest.java):

- **Layers**, bottom-up: JSON, Diagnostics, Model, then Parser, Interpreter, Checker and C backend, then
  the CLI, with the API on top. Only the API uses the CLI, so a host's call runs exactly as `alang call`;
  its public methods use only JDK and `alang.api` types.
- **One value per constant**: the checker evaluates constants with `Interpreter.evalConst`; the C backend
  turns those values into C initializers.
- **ANTLR stays in the parser**; **the AST is written once**, by the parser and the checker.
- **I/O at the edge**: only the CLI and API touch files, only the CLI stdin or `System.exit`. Nothing reads
  a clock, randomness or the environment, reflects, logs or depends on the locale. The exceptions are in
  `serve`: call threads, a `--timeout` watchdog, the `--watch` poller, and `HttpTransport` (clock for logs
  and metrics, random session ids, a shutdown hook). Each call is still one run on one thread, which
  another can only stop through its `Meter`.

## A tool call

`run` and `test` take the same path to the interpreter; `build` hands the checked program to the C backend.

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
    CLI-->>Host: {"ok": true, "result": [...], "stdout": "", "steps": 45}
```
