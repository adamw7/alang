# ALang vs Python: an agent benchmark

[docs/COMPARISON.md](../docs/COMPARISON.md) argues that ALang's guarantees make agent-written tools
safer. This benchmark checks whether that holds:

1. **How often does a model write a correct tool**, in ALang and in Python?
2. **When the tool is wrong, does anyone find out?** A wrong value with no error is the failure an agent
   loop cannot catch; a trap or an exception at least says something broke.

ALang's claim is about the second question: fewer silent failures, because overflow, bad indexes,
runaway loops and similar mistakes trap instead of producing a value. It may well lose on the first,
since models have seen far more Python. The benchmark reports both. No results are checked in yet; see
[Running it](#running-it).

## How it works

20 small, self-contained tasks of the kind an agent writes as a tool: parsing, validation, checksums,
date math, text formatting, search. For each task and language the harness holds one conversation:

1. The model gets the task, the exact signature and three examples, and writes the tool.
2. The harness checks the program (`alang check`, or compiling and importing the Python) and runs it on
   the examples.
3. On any failure the model gets the same kind of feedback in both languages (the diagnostics, which
   example returned what, or the error raised) and tries again, up to `--attempts` replies (default 3).
4. The first and last programs run on the task's hidden cases (15 to 30 per task, 372 in all), which the
   model never sees.

Every hidden case lands in one of four buckets:

| Outcome | Meaning |
|---------|---------|
| correct | the expected value |
| **wrong** | a different value, and no error: a silent failure |
| error | a trap, an exception, a timeout or a result that is not JSON: a loud failure |
| rejected | the program never ran: compile error, wrong signature, or a failure while importing |

The headline is **"Passed the examples, but silently wrong on hidden cases"**: the conversation ended
with a program that passed every visible example, so an agent would have shipped it, yet it returns wrong
values without any error on some valid inputs.

## The tasks

Some tasks favor one language; the table lists the pitfalls we expected, so you can judge the balance.
"Neutral" means neither language's semantics obviously helps.

| Task | What it does | Expected pitfall |
|------|--------------|------------------|
| `balanced` | bracket matching | neutral |
| `compare_versions` | compare "1.10.2" with "1.9" | neutral (string instead of numeric comparison) |
| `days_between` | days between two dates | favors Python: `datetime` does it all |
| `fnv1a_32` | 32-bit FNV-1a hash of UTF-8 bytes | Python: hashing characters instead of bytes, a missing `mod 2^32` gives silently huge values. ALang: a plain `u32` multiply traps, and `wrapping_mul` is the fix |
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

Each task lives in `tasks/<name>/`: `prompt.md` (the description, shared by both languages),
`signature.al`/`.py`, `reference.al`/`.py` and `cases.json` (the visible examples and the hidden cases).
[`make_cases.py`](make_cases.py) builds `cases.json` from hand-picked edge cases plus a few seeded random
ones, takes the expected values from `reference.py`, and writes nothing unless `reference.al` agrees on
every case and on 40 more random inputs per task.

## Running it

Requires JDK 21 and Maven (`target/alang.jar` is built on first use), Python 3.11 or newer, and the
Anthropic SDK with an API key:

```sh
pip install -r bench/requirements.txt
export ANTHROPIC_API_KEY=...

python3 bench/bench.py selftest                       # offline: tasks, grader and loop work (~1.5 min)
python3 bench/bench.py run --tasks mul_mod            # a small paid run, to see the cost
python3 bench/bench.py run --samples 3                # the full benchmark, 120 conversations
python3 bench/bench.py report bench/results/*.jsonl   # Markdown summary of one or more runs
```

`run` prints progress and the report, and writes every conversation (each reply's code, the feedback,
token usage and grading, but not the prompts) to `bench/results/<time>-<model>.jsonl`. Continue an
interrupted run with `--resume <file>`; `run --model reference` is a free dry run in which the reference
solutions play the model.

| Option | Default | |
|--------|---------|---|
| `--model` | `claude-opus-5-5` | any Claude model id, or `reference` |
| `--effort` | `high` | `low` to `max`, or `default` to leave it to the model |
| `--attempts` | 3 | replies per conversation, feedback rounds included |
| `--samples` | 1 | conversations per task and language; use 3 or more, sampling varies |
| `--tasks`, `--langs` | all | comma-separated subsets |
| `--jobs` | 4 | conversations in parallel |
| `--no-fallbacks` | off | see below |

**Cost.** One sample of the full set is 40 conversations of one to three replies. ALang conversations also
send the language guide ([AGENTS.md](../AGENTS.md), about 4,500 tokens); the harness caches the prompt
prefix, so later replies read it from the cache. The report shows input and output tokens per language.
Run a single task first and scale from there.

**Model settings.** Requests use adaptive thinking and the chosen effort. For models that support it
(`claude-opus-5-5`, `claude-opus-5`, `claude-sonnet-5-5`, `claude-fable-5-1`), a request the model
declines falls back to another model on the server (`fallbacks: "default"`). Coding tasks should never
trigger that, but every reply records which model served it, and the report counts refusals and fallback
replies so they cannot skew a comparison silently. `--no-fallbacks` turns it off.

## Fairness and limits

Held equal: the task text, the examples, the hidden cases, the shape of the feedback, the number of
attempts and the model settings. Different by design: only the ALang conversation includes a language
guide, because no model knows ALang otherwise.

- **Small tasks.** Tool-sized functions, not applications: ALang's home ground.
- **Who wrote the tasks.** The tasks, examples and hidden cases come from the ALang project; check the
  pitfall table against your own expectations.
- **No tool use.** The model cannot run its own code; it sees only the harness's feedback on the
  examples. Real agents can test more, in both languages.
- **Sampling.** Results vary between runs: use `--samples 3` or more and look at the spread.
- **Python runs unsandboxed.** Each Python call runs in a fresh process with a 10 s CPU limit and a 2 GB
  memory limit, but as you, with access to your files: run the benchmark in a container or VM. ALang
  calls need no sandbox: they cannot reach the host, and stop deterministically after 10 million
  steps, a second or two of work.
- **ALang is version 0.2.** The guide and diagnostics will change; the results file records the revision
  as `alang_revision`.

## Files

```
bench.py           the harness: run, report, selftest
make_cases.py      builds tasks/*/cases.json and cross-checks the two reference solutions
requirements.txt   the Anthropic SDK
tasks/<name>/      prompt.md, signature.al/.py, reference.al/.py, cases.json
selftest/          known-bad programs; the selftest checks they are graded wrong / error / rejected
results/           run output (JSON Lines: one header line, then one line per conversation)
```

To add a task, create `tasks/<name>/` with the five source files, add its inputs to `INPUTS` in
`make_cases.py`, run `python3 bench/make_cases.py <name>`, then `python3 bench/bench.py selftest`.
