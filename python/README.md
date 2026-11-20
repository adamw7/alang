# ALang

**An agent-first programming language: no heap, fully deterministic, always terminates.** A small,
statically typed language that AI agents write and hosts run safely.

```sh
pip install alang-toolchain
alang version
```

This installs the `alang` command and an `alang` Python module. On Linux (x64, arm64, glibc 2.35 or newer),
macOS (arm64) and Windows (x64) the wheel carries the native binary, which needs no Java. Anywhere else pip
installs the wheel with the jar, which needs Java 21 or newer: `ALANG_JAVA`, `JAVA_HOME` or `java` on PATH,
or a Java runtime from pip with `pip install "alang-toolchain[java]"`.

## The command

```sh
alang check prog.al --json    # diagnostics: code, line, column, message, hint
alang test  prog.al           # run every `test "..." { }` block
alang run   prog.al < in.txt  # run fn main()
alang call  prog.al word_counts '{"text": "a b a"}'
alang serve prog.al           # every `tool fn` over MCP on stdio
```

`python -m alang` is the same command.

## From Python

```python
import alang

report = alang.check("prog.al")                     # alang check --json, as a dict
for d in report["diagnostics"]:
    print(d["code"], d["line"], d["message"], d["hint"])

alang.tools("prog.al")                              # tool definitions to hand a model
alang.call("prog.al", "word_counts", {"text": "a b a"})
# {"ok": True, "result": [{"word": "a", "count": 2}, {"word": "b", "count": 1}], "stdout": "", "steps": 45}

guide = alang.agents_md()                           # AGENTS.md: put it in the agent's context
proc = alang.invoke("run", "prog.al", input="1\n2\n")  # any command, as a subprocess.CompletedProcess
```

Each call starts the toolchain in its own process: a tool runs with no file, network, clock or random
access, within a deterministic step budget.

Start with [AGENTS.md](https://github.com/adamw7/alang/blob/main/AGENTS.md); the full rules are in
[SPEC.md](https://github.com/adamw7/alang/blob/main/docs/SPEC.md).
