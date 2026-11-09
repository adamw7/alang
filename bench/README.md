# ALang vs Python: an agent benchmark

[docs/COMPARISON.md](../docs/COMPARISON.md) argues that ALang's guarantees make agent-written tools
safer. This benchmark checks whether that holds in practice. It asks two questions:

1. **How often does a model write a correct tool**, in ALang and in Python?
2. **When the tool is wrong, does anyone find out?** A wrong value returned with no error is the
   failure an agent loop cannot catch. A trap or an exception at least says that something broke.

ALang's claim is about the second question: fewer silent failures, because overflow, bad indexes,
runaway loops and similar mistakes trap instead of producing a value. It may well lose on the first
question, since models have seen far more Python than ALang. The benchmark reports both.

No results are checked in yet; the first run is yours to make (see [Running it](#running-it)).

## How it works

There are 20 small tasks, the kind of self-contained function an agent writes as a tool: parsing,
validation, checksums, date math, text formatting, search. For each task and language, the harness
holds one conversation with the model:

1. The model gets the task description, the exact signature and three examples, and writes the tool.
2. The harness checks the program (`alang check` for ALang, compiling and importing for Python) and
   runs it on the three examples.
3. If anything fails, the model sees the same kind of feedback in both languages (the checker's
   diagnostics, or which example returned what, or the error it raised) and tries again, up to
   `--attempts` replies in total (3 by default).
4. The first and the last program are then run on the task's hidden cases (15 to 30 per task, 372 in
   total), which the model never sees.

Every hidden case ends up in one of four buckets:

| Outcome | Meaning |
|---------|---------|
| correct | the expected value |
| **wrong** | a different value, and no error: a silent failure |
| error | a trap, an exception, a timeout or a result that is not JSON: a loud failure |
| rejected | the program never ran: compile error, wrong signature, or a failure while importing |

The headline row of the report is **"Passed the examples, but silently wrong on hidden cases"**: the
conversation ended with a program that passed every example the model could see, so an agent would
have shipped it, but it returns wrong values without any error on some valid inputs.

## The tasks

Some tasks favor one language. The table lists the pitfalls we expected, so you can judge whether the
set is balanced. "Neutral" means neither language's semantics obviously helps.

| Task | What it does | Expected pitfall |
|------|--------------|------------------|
| `balanced` | bracket matching | neutral |
| `compare_versions` | compare "1.10.2" with "1.9" | neutral (string instead of numeric comparison) |
| `days_between` | days between two dates | favors Python: `datetime` does it all |
| `fnv1a_32` | 32-bit FNV-1a hash of UTF-8 bytes | Python: hashing characters instead of bytes, a missing `mod 2^32` gives silently huge values. ALang: a `u32` multiply traps |
| `format_thousands` | `1234567` to `"1,234,567"` | favors Python: `f"{n:,}"`. ALang: `abs(i64 min)` traps |
| `grid_path` | shortest path in a small maze | neutral |
| `insertion_point` | leftmost insertion index in a sorted list | neutral |
| `int_to_roman` | integer to Roman numeral | neutral |
| `ipv4_to_int` | strict dotted IPv4 parsing | Python: `int()` also accepts `" 1"`, `"+1"`, `"1\n"` and `"٤"` |
| `levenshtein` | edit distance | neutral |
| `luhn_valid` | Luhn checksum | neutral |
| `max_drawdown` | largest drop in a price series | neutral |
| `merge_intervals` | merge overlapping intervals (structs) | neutral |
| `mul_mod` | `(a * b) mod m` for `m` up to 2^62 | favors Python: unbounded integers. ALang: the naive product traps |
| `parse_duration` | `"1h30m"` to seconds, strict | Python: `int()` leniency, as above |
| `rle_encode` | run-length encoding | neutral |
| `roman_to_int` | Roman numeral to integer | neutral |
| `rotate_left` | rotate a list, `k` may be negative | favors Python: `%` is never negative. ALang: a negative index traps |
| `top_words` | most frequent words, ties alphabetical | Python: `Counter.most_common` breaks ties by first occurrence |
| `word_wrap` | greedy word wrap | Python: `textwrap` splits at hyphens by default |

Each task lives in `tasks/<name>/`: `prompt.md` (the description, the same for both languages),
`signature.al` and `signature.py`, `reference.al` and `reference.py`, and `cases.json` (the visible
examples and the hidden cases). [`make_cases.py`](make_cases.py) builds `cases.json`: it lists the
hand-picked edge cases of every task, adds a few seeded random cases, takes the expected values from
`reference.py`, and refuses to write anything unless `reference.al` agrees on every case and on 40
more random inputs per task.

## Running it

Requirements: JDK 21 and Maven (for `target/alang.jar`, built on first use), Python 3.11 or newer,
and the Anthropic SDK with an API key:

```sh
pip install -r bench/requirements.txt
export ANTHROPIC_API_KEY=...

python3 bench/bench.py selftest                       # offline: tasks, grader and loop work (~1.5 min)
python3 bench/bench.py run --tasks mul_mod            # a small paid run, to see the cost
python3 bench/bench.py run --samples 3                # the full benchmark, 120 conversations
python3 bench/bench.py report bench/results/*.jsonl   # Markdown summary of one or more runs
```

`run` prints progress, writes every conversation (prompts aside: every reply's code, the feedback,
the token usage and the grading) to `bench/results/<time>-<model>.jsonl`, and prints the report.
An interrupted run continues with `--resume <file>`. `run --model reference` is a free dry run in
which the reference solutions play the model.

| Option | Default | |
|--------|---------|---|
| `--model` | `claude-opus-5-5` | any Claude model id, or `reference` |
| `--effort` | `high` | `low` to `max`, or `default` to leave it to the model |
| `--attempts` | 3 | replies per conversation, feedback rounds included |
| `--samples` | 1 | conversations per task and language; use 3 or more, sampling varies |
| `--tasks`, `--langs` | all | comma-separated subsets |
| `--jobs` | 4 | conversations in parallel |
| `--no-fallbacks` | off | see below |

**Cost.** One sample of the full set is 40 conversations of one to three replies. ALang conversations
also send the language guide ([AGENTS.md](../AGENTS.md), about 2,500 tokens); the harness caches the
prompt prefix, so later replies read it from the cache. The report shows input and output tokens per
language. Run a single task first and scale from there.

**Model settings.** Requests use adaptive thinking and the chosen effort. For models that support it
(`claude-opus-5-5`, `claude-opus-5`, `claude-sonnet-5-5`, `claude-fable-5-1`), a request that the
model declines falls back to another model on the server (`fallbacks: "default"`). Coding tasks like
these should never trigger that, but every reply records which model served it, and the report
counts refusals and fallback replies so they cannot skew a comparison silently. Pass `--no-fallbacks`
to turn it off.

## Fairness and limits

What is held equal: the task text, the examples, the hidden cases, the shape of the feedback, the
number of attempts and the model settings. What differs, by design: the ALang conversation includes
the language guide, because no model knows ALang otherwise; the Python one has no guide.

Read the results with these limits in mind:

- **Small tasks.** These are tool-sized functions, not applications. ALang targets exactly this kind
  of code, so this is its home ground.
- **Who wrote the tasks.** The tasks, examples and hidden cases come from the same project as ALang.
  The pitfall table above shows where we expected each language to stumble; check it against your own
  expectations.
- **No tool use.** The model cannot run its own code; it only sees the harness's feedback on the
  examples. Real agents can test more, in both languages.
- **Sampling.** Results vary from run to run. Use `--samples 3` or more and look at the spread before
  drawing conclusions from small differences.
- **Python runs unsandboxed.** Each Python call runs in a fresh process with a 10 s CPU limit and a
  2 GB memory limit, but it runs as you and can touch your files. Run the benchmark in a container or
  a VM. ALang calls need no sandbox: they cannot reach the host, and they stop after 10 million
  executed statements, deterministically.
- **ALang is version 0.1.** The guide and the diagnostics will change; record the revision (the
  results file does, as `alang_revision`).

## Files

```
bench.py           the harness: run, report, selftest
make_cases.py      builds tasks/*/cases.json and cross-checks the two reference solutions
requirements.txt   the Anthropic SDK
tasks/<name>/      prompt.md, signature.al/.py, reference.al/.py, cases.json
selftest/          known-bad programs; the selftest checks they are graded wrong / error / rejected
results/           run output (JSON Lines: one header line, then one line per conversation)
```

To add a task: create `tasks/<name>/` with the five source files, add its inputs to `INPUTS` in
`make_cases.py`, run `python3 bench/make_cases.py <name>`, then `python3 bench/bench.py selftest`.
