#!/usr/bin/env python3
"""ALang vs Python: how often does a model write a correct tool, and how do its mistakes show up?

    python3 bench/bench.py run [--model claude-opus-5-5] [--samples 3] ...   ask a model, grade, save
    python3 bench/bench.py report bench/results/*.jsonl                       summarize as Markdown
    python3 bench/bench.py selftest                                          offline checks, no API calls

For every task the model writes the same tool once in ALang and once in Python. After each reply it is
told whether the program compiles and whether it passes the task's visible examples, and it may try
again. The final program is then graded on hidden cases, and every hidden case ends up as one of:

    correct   the expected value
    wrong     a different value, with no error: the silent failure the benchmark is about
    error     a trap, exception, timeout or invalid result: a loud failure
    rejected  the program never ran (compile error, wrong signature, error while importing)

See bench/README.md for the method and how to read the results.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import contextlib
import dataclasses
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench"
TASKS_DIR = BENCH / "tasks"
SELFTEST_DIR = BENCH / "selftest"
RESULTS_DIR = BENCH / "results"
JAR = ROOT / "target" / "alang.jar"
GUIDE = ROOT / "AGENTS.md"

LANGS = ("alang", "python")
LANG_NAME = {"alang": "ALang", "python": "Python"}
EXT = {"alang": ".al", "python": ".py"}

DEFAULT_MODEL = "claude-opus-5-5"
# Models that accept the server-side refusal fallback (`fallbacks: "default"`). A reply served by a
# fallback model is recorded, and the report counts such replies.
FALLBACK_MODELS = {"claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5"}

ALANG_MAX_STEPS = 10_000_000  # deterministic statement budget per ALang call
PYTHON_CPU_SECONDS = 10       # CPU limit per Python call
CASE_TIMEOUT = 30             # wall-clock limit per call, both languages
MEMORY_LIMIT = 2 << 30        # address-space limit per Python call

_slots = threading.BoundedSemaphore(os.cpu_count() or 2)  # concurrent subprocesses
_cache: dict[tuple, "Graded"] = {}
_cache_lock = threading.Lock()


def canon(value) -> str:
    """Canonical JSON: equal values give equal text, and true != 1."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def short(value, limit=200) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    return text if len(text) <= limit else text[:limit] + "..."


# =============================================================================== tasks

@dataclasses.dataclass(frozen=True)
class Task:
    name: str
    prompt: str
    signature: dict
    reference: dict
    examples: list
    hidden: list


def load_tasks(names: list[str] | None = None) -> list[Task]:
    tasks = []
    for d in sorted(p for p in TASKS_DIR.iterdir() if p.is_dir()):
        if names and d.name not in names:
            continue
        cases = json.loads((d / "cases.json").read_text())
        tasks.append(Task(
            name=d.name,
            prompt=(d / "prompt.md").read_text().strip(),
            signature={lang: (d / f"signature{EXT[lang]}").read_text().strip() for lang in LANGS},
            reference={lang: (d / f"reference{EXT[lang]}").read_text() for lang in LANGS},
            examples=cases["examples"],
            hidden=cases["hidden"],
        ))
    missing = set(names or []) - {t.name for t in tasks}
    if missing:
        sys.exit(f"bench: unknown task(s): {', '.join(sorted(missing))}")
    return tasks


# =============================================================================== running programs

def _run(cmd, cwd, stdin: bytes = b"", timeout=CASE_TIMEOUT):
    """Runs a command; returns (exit code, stdout, stderr), with exit code None on timeout."""
    with _slots:
        try:
            p = subprocess.run(cmd, cwd=cwd, input=stdin, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return None, "", ""
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")


def _without_jvm_noise(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not line.startswith("Picked up ")).strip()


def ensure_jar():
    """Builds target/alang.jar if it is missing or older than the sources, so results match the revision."""
    sources = [f for d in ("src/main", "grammar") for f in (ROOT / d).rglob("*") if f.is_file()]
    if not JAR.exists() or max(f.stat().st_mtime for f in sources) > JAR.stat().st_mtime:
        print("bench: building target/alang.jar ...", file=sys.stderr)
        subprocess.run(["mvn", "-q", "-B", "-DskipTests", "package"], cwd=ROOT, check=True)


def _alang(*args):
    return ["java", "-XX:TieredStopAtLevel=1", "-jar", str(JAR), *args]


# The Python runner: imports the solution in a fresh interpreter, calls the function with the JSON
# arguments from stdin and prints one marked JSON line. stdout of the solution itself is discarded.
PY_RUNNER = r'''
import contextlib, importlib.util, inspect, io, json, sys, traceback
try:
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (__CPU__, __CPU__))
    resource.setrlimit(resource.RLIMIT_AS, (__MEM__, __MEM__))
except (ImportError, ValueError, OSError):
    pass
mode, path, name = sys.argv[1:4]
out = sys.stdout

def emit(obj):
    out.write("\n@@bench@@" + json.dumps(obj) + "\n")
    out.flush()

try:
    with contextlib.redirect_stdout(io.StringIO()):
        spec = importlib.util.spec_from_file_location("solution", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        fn = getattr(module, name, None)
        if not callable(fn):
            raise NameError(f"the module does not define a function `{name}`")
        if mode == "signature":
            result = [p.name for p in inspect.signature(fn).parameters.values()]
        else:
            result = fn(**json.loads(sys.stdin.read()))
    emit({"ok": True, "result": json.loads(json.dumps(result, allow_nan=False))})
except BaseException as e:
    frames = [f for f in traceback.extract_tb(e.__traceback__) if f.filename == path]
    where = f" (line {frames[-1].lineno})" if frames else ""
    emit({"ok": False, "error": f"{type(e).__name__}: {e}{where}"})
'''.replace("__CPU__", str(PYTHON_CPU_SECONDS)).replace("__MEM__", str(MEMORY_LIMIT))


def _python(*args):
    return [sys.executable, "-I", "-c", PY_RUNNER, *args]


def _parse_python(code, stdout):
    marked = [line for line in stdout.splitlines() if line.startswith("@@bench@@")]
    if marked:
        return json.loads(marked[-1][len("@@bench@@"):])
    if code is None:
        return {"ok": False, "error": f"timed out after {CASE_TIMEOUT} s"}
    if code < 0:
        return {"ok": False, "error": f"killed by signal {-code} (CPU limit is {PYTHON_CPU_SECONDS} s)"}
    return {"ok": False, "error": f"the process exited with code {code} without a result"}


def check_program(lang: str, task: Task, workdir: Path) -> str | None:
    """Returns None if the program can run, otherwise the feedback explaining why not."""
    wanted = signature_params(lang, task)
    if lang == "alang":
        code, out, err = _run(_alang("check", "solution.al"), workdir)
        if code != 0:
            return "`alang check` rejected the program:\n\n" + _without_jvm_noise(err + "\n" + out)
        code, out, _ = _run(_alang("tools", "solution.al"), workdir)
        tools = {t["name"]: t for t in json.loads(out)} if code == 0 else {}
        tool = tools.get(task.name)
        params = list(tool["input_schema"]["properties"]) if tool else None
    else:
        source = (workdir / "solution.py").read_text()
        try:
            compile(source, "solution.py", "exec")
        except SyntaxError as e:
            return "Python rejected the program:\n\n" + "".join(traceback.format_exception_only(e)).strip()
        code, out, _ = _run(_python("signature", "solution.py", task.name), workdir)
        result = _parse_python(code, out)
        if not result["ok"]:
            return "The module failed to load:\n\n" + result["error"]
        params = result["result"]
    if params != wanted:
        return ("The program must define the tool with exactly this signature (same name and parameters):\n\n"
                + task.signature[lang])
    return None


_params: dict[tuple, list] = {}


def signature_params(lang: str, task: Task) -> list:
    """Parameter names of the reference solution."""
    key = (lang, task.name)
    if key not in _params:
        if lang == "alang":
            m = re.search(r"tool fn \w+\((.*?)\)", task.signature[lang], re.S)
        else:
            m = re.search(r"def \w+\((.*?)\)", task.signature[lang], re.S)
        _params[key] = [p.split(":")[0].strip() for p in m.group(1).split(",") if p.strip()]
    return _params[key]


def call(lang: str, task: Task, workdir: Path, args: dict) -> dict:
    """Runs one call; returns {"ok": true, "result": value} or {"ok": false, "error": text}."""
    stdin = json.dumps(args).encode()  # on stdin: argv would be decoded with the host locale
    if lang == "alang":
        code, out, _ = _run(_alang("call", "solution.al", task.name, "--max-steps", str(ALANG_MAX_STEPS)),
                            workdir, stdin)
        if code is None:
            return {"ok": False, "error": f"timed out after {CASE_TIMEOUT} s"}
        try:
            res = json.loads(out.strip().splitlines()[-1])
        except (ValueError, IndexError):
            return {"ok": False, "error": f"`alang call` exited with code {code} without a result"}
        if res.get("ok"):
            return {"ok": True, "result": res["result"]}
        e = res.get("error", {})
        where = f" (line {e['line']})" if e.get("line") else ""
        return {"ok": False, "error": f"{e.get('code')}: {e.get('message')}{where}"}
    code, out, _ = _run(_python("call", "solution.py", task.name), workdir, stdin)
    return _parse_python(code, out)


@dataclasses.dataclass
class Graded:
    rejected: str | None  # why the program could not run, or None
    kinds: list            # per case: correct / wrong / error / rejected
    got: list              # per case: the returned value or the error text

    @property
    def passed(self) -> bool:
        return self.rejected is None and all(k == "correct" for k in self.kinds)

    def summary(self, cases) -> dict:
        fails = [{"case": i, "kind": k, "args": c["args"], "expect": c["expect"], "got": g}
                 for i, (k, g, c) in enumerate(zip(self.kinds, self.got, cases)) if k != "correct"]
        return {"rejected": self.rejected, "kinds": self.kinds, "fails": fails}


def grade(lang: str, task: Task, code: str | None, cases: list, label: str) -> Graded:
    """Checks a program and runs it on every case. Results are cached per program and case set."""
    if code is None:
        return Graded("no code block in the reply", ["rejected"] * len(cases), [None] * len(cases))
    key = (lang, task.name, label, hashlib.sha256(code.encode()).hexdigest())
    with _cache_lock:
        if key in _cache:
            return _cache[key]
    with tempfile.TemporaryDirectory(prefix="alang-bench-") as tmp:
        workdir = Path(tmp)
        (workdir / f"solution{EXT[lang]}").write_text(code)
        rejected = check_program(lang, task, workdir)
        if rejected:
            graded = Graded(rejected, ["rejected"] * len(cases), [None] * len(cases))
        else:
            with concurrent.futures.ThreadPoolExecutor(os.cpu_count() or 2) as pool:
                outcomes = list(pool.map(lambda c: call(lang, task, workdir, c["args"]), cases))
            kinds, got = [], []
            for case, o in zip(cases, outcomes):
                if not o["ok"]:
                    kinds.append("error")
                    got.append(o["error"])
                else:
                    kinds.append("correct" if canon(o["result"]) == canon(case["expect"]) else "wrong")
                    got.append(o["result"])
            graded = Graded(None, kinds, got)
    with _cache_lock:
        _cache[key] = graded
    return graded


# =============================================================================== prompts

def system_prompt(lang: str) -> str:
    if lang == "alang":
        return f"""You write tools in ALang, a small programming language for code written by AI agents. This is its guide:

<alang_guide>
{GUIDE.read_text().strip()}
</alang_guide>

When you are given a task, reply with one complete ALang program in a single ```alang code block. The program must declare the `tool fn` with exactly the signature given in the task: the same name and the same parameter names and types. Add any helper functions, constants, structs, enums and `test` blocks you need; `fn main()` is not needed. You cannot run the program yourself: after each reply you will be told whether it compiles and whether it returns the expected results for the examples."""
    return """You write tools in Python 3.

When you are given a task, reply with one complete Python module in a single ```python code block. The module must define a function with exactly the signature given in the task: the same name and the same parameter names. Add any helper functions you need, and use only the standard library. The function is called directly with JSON values as arguments (int, bool, str, list, dict) and must return a JSON value; it must not read input or print. You cannot run the program yourself: after each reply you will be told whether it runs and whether it returns the expected results for the examples."""


def render_call(task: Task, args: dict) -> str:
    return f"{task.name}({json.dumps(args, ensure_ascii=False)})"


def task_prompt(task: Task, lang: str) -> str:
    examples = "\n".join(f"- `{render_call(task, c['args'])}` returns `{json.dumps(c['expect'], ensure_ascii=False)}`"
                         for c in task.examples)
    return f"""Write the tool `{task.name}` in {LANG_NAME[lang]}.

{task.prompt}

Signature:

```{lang}
{task.signature[lang]}
```

Examples (arguments and results as JSON):

{examples}

Reply with the complete program in one ```{lang} code block."""


def feedback(task: Task, lang: str, graded: Graded) -> str:
    again = f"Fix the program and reply with the complete corrected program in one ```{lang} code block."
    if graded.rejected:
        return f"{graded.rejected}\n\n{again}"
    lines = []
    for case, kind, got in zip(task.examples, graded.kinds, graded.got):
        if kind == "wrong":
            lines.append(f"- `{render_call(task, case['args'])}` should return "
                         f"`{short(case['expect'])}`, but it returned `{short(got)}`.")
        elif kind == "error":
            lines.append(f"- `{render_call(task, case['args'])}` failed: {short(got, 400)}")
    failed = sum(k != "correct" for k in graded.kinds)
    return f"The program runs, but {failed} of {len(graded.kinds)} examples fail:\n\n" + "\n".join(lines) + f"\n\n{again}"


FENCED = re.compile(r"```([^\n`]*)\n(.*?)```", re.S)


def extract_code(text: str, lang: str) -> str | None:
    """The longest code block tagged with the language, else the longest untagged block."""
    tags = {"alang": {"alang", "al"}, "python": {"python", "py", "python3"}}[lang]
    blocks = [(info.strip().lower(), body) for info, body in FENCED.findall(text)]
    tagged = [b for i, b in blocks if i.split(" ")[0] in tags]
    untagged = [b for i, b in blocks if not i]
    pick = tagged or untagged
    return max(pick, key=len) if pick else None


# =============================================================================== models

@dataclasses.dataclass
class Reply:
    text: str
    content: object  # appended to the conversation as the assistant turn
    stop_reason: str
    model: str
    fallback: bool
    usage: dict


class ClaudeModel:
    def __init__(self, model: str, effort: str, fallbacks: bool, client=None):
        if client is None:
            try:
                import anthropic
            except ImportError:
                sys.exit("bench: the Anthropic SDK is missing; run: pip install -r bench/requirements.txt")
            client = anthropic.Anthropic(max_retries=8)  # credentials from the environment
        self.client = client
        self.model = model
        self.effort = effort
        self.fallbacks = fallbacks and model in FALLBACK_MODELS

    def reply(self, system: str, messages: list, task: Task, lang: str) -> Reply:
        kwargs = dict(
            model=self.model,
            max_tokens=64000,
            system=system,
            messages=messages,
            thinking={"type": "adaptive"},
            cache_control={"type": "ephemeral"},  # the system prompt and earlier turns are reused
        )
        if self.effort != "default":
            kwargs["output_config"] = {"effort": self.effort}
        if self.fallbacks:
            kwargs.update(betas=["server-side-fallback-2026-07-01"], fallbacks="default")
        with self.client.beta.messages.stream(**kwargs) as stream:
            message = stream.get_final_message()
        u = message.usage
        iterations = getattr(u, "iterations", None) or []
        return Reply(
            text="".join(b.text for b in message.content if b.type == "text"),
            content=message.content,
            stop_reason=message.stop_reason,
            model=message.model,
            fallback=any(getattr(i, "type", None) == "fallback_message" for i in iterations),
            usage={
                "input_tokens": u.input_tokens,
                "output_tokens": u.output_tokens,
                "cache_read_input_tokens": u.cache_read_input_tokens or 0,
                "cache_creation_input_tokens": u.cache_creation_input_tokens or 0,
            },
        )


class ScriptedModel:
    """Offline stand-in: replies with given programs in order (the last one repeats)."""

    def __init__(self, name: str, programs):
        self.model = name
        self.programs = programs  # function (task, lang) -> list of program texts

    def reply(self, system, messages, task: Task, lang: str) -> Reply:
        programs = self.programs(task, lang)
        turn = sum(1 for m in messages if m["role"] == "assistant")
        program = programs[min(turn, len(programs) - 1)]
        text = f"```{lang}\n{program}```\n"
        return Reply(text, text, "end_turn", self.model, False, {})


def reference_model() -> ScriptedModel:
    return ScriptedModel("reference", lambda task, lang: [task.reference[lang]])


# =============================================================================== one sample

def solve(task: Task, lang: str, model, max_attempts: int) -> dict:
    """One conversation: the model writes the tool, gets feedback on the examples, tries again."""
    system = system_prompt(lang)
    messages = [{"role": "user", "content": task_prompt(task, lang)}]
    attempts = []
    for n in range(1, max_attempts + 1):
        r = model.reply(system, messages, task, lang)
        code = extract_code(r.text, lang)
        attempt = {"n": n, "code": code, "stop_reason": r.stop_reason, "model": r.model,
                   "fallback": r.fallback, "usage": r.usage}
        attempts.append(attempt)
        if r.stop_reason == "refusal":
            break
        visible = grade(lang, task, code, task.examples, "examples")
        attempt["rejected"] = visible.rejected is not None
        attempt["examples"] = visible.kinds
        if visible.passed:
            break
        if code is None:
            reason = "it was cut off" if r.stop_reason == "max_tokens" else "there is no code block"
            note = f"I could not find the program in your reply ({reason}). Reply with the complete program in one ```{lang} code block."
        else:
            note = feedback(task, lang, visible)
        attempt["feedback"] = note
        messages += [{"role": "assistant", "content": r.content}, {"role": "user", "content": note}]
    codes = [a["code"] for a in attempts if a["code"] is not None]
    first = attempts[0]["code"]
    final = codes[-1] if codes else None
    return {
        "type": "sample",
        "task": task.name,
        "lang": lang,
        "attempts": attempts,
        "passed_examples": bool(attempts[-1].get("examples")) and all(k == "correct" for k in attempts[-1]["examples"]),
        "first": grade(lang, task, first, task.hidden, "hidden").summary(task.hidden),
        "final": grade(lang, task, final, task.hidden, "hidden").summary(task.hidden),
    }


# =============================================================================== run

def git_revision() -> str:
    try:
        rev = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True)
        dirty = subprocess.run(["git", "status", "--porcelain", "--", "src", "grammar", "AGENTS.md"], cwd=ROOT,
                               capture_output=True, text=True).stdout.strip()
        return rev.stdout.strip() + ("+dirty" if dirty else "")
    except OSError:
        return "unknown"


def sdk_version() -> str | None:
    try:
        import anthropic
        return anthropic.__version__
    except ImportError:
        return None


def cmd_run(a) -> int:
    ensure_jar()
    done = set()
    if a.resume:  # same settings as the interrupted run
        out = Path(a.resume)
        lines = [json.loads(line) for line in out.read_text().splitlines() if line.strip()]
        h = lines[0]
        a.model, a.effort, a.attempts, a.samples = h["model"], h["effort"], h["max_attempts"], h["samples"]
        a.tasks, a.langs, a.no_fallbacks = ",".join(h["tasks"]), ",".join(h["langs"]), not h["fallbacks"]
        done = {(r["task"], r["lang"], r["sample"]) for r in lines if r["type"] == "sample"}
    tasks = load_tasks(a.tasks.split(",") if a.tasks else None)
    langs = a.langs.split(",")
    model = reference_model() if a.model == "reference" else ClaudeModel(a.model, a.effort, not a.no_fallbacks)
    if not a.resume:
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
        out = Path(a.out) if a.out else RESULTS_DIR / f"{stamp}-{a.model}.jsonl"
        out.parent.mkdir(parents=True, exist_ok=True)
        header = {"type": "run", "model": a.model, "effort": a.effort, "fallbacks": getattr(model, "fallbacks", False),
                  "max_attempts": a.attempts, "samples": a.samples, "tasks": [t.name for t in tasks], "langs": langs,
                  "alang_revision": git_revision(), "python": sys.version.split()[0], "sdk": sdk_version(),
                  "alang_max_steps": ALANG_MAX_STEPS, "python_cpu_seconds": PYTHON_CPU_SECONDS,
                  "started": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
        out.write_text(json.dumps(header) + "\n")
    units = [(t, lang, s) for s in range(1, a.samples + 1) for t in tasks for lang in langs
             if (t.name, lang, s) not in done]
    print(f"bench: {len(units)} conversations with {a.model} -> {out}", file=sys.stderr)
    lock = threading.Lock()
    failures = 0

    def work(unit):
        task, lang, sample = unit
        try:
            record = solve(task, lang, model, a.attempts)
        except Exception as e:  # an API error after retries: record it, keep going, --resume retries it
            record = {"type": "failure", "task": task.name, "lang": lang, "error": f"{type(e).__name__}: {e}"}
        record["sample"] = sample
        with lock:
            with out.open("a") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
        return record

    with concurrent.futures.ThreadPoolExecutor(a.jobs) as pool:
        for i, record in enumerate(pool.map(work, units), 1):
            if record["type"] == "failure":
                failures += 1
                status = "FAILED: " + record["error"]
            else:
                kinds = record["final"]["kinds"]
                status = (f"{len(record['attempts'])} attempt(s), hidden {kinds.count('correct')}/{len(kinds)} correct"
                          + (f", {kinds.count('wrong')} silently wrong" if "wrong" in kinds else ""))
            print(f"[{i}/{len(units)}] {record['task']} {LANG_NAME[record['lang']]} #{record['sample']}: {status}",
                  file=sys.stderr)
    print(f"bench: wrote {out}" + (f" ({failures} failed; rerun with --resume {out})" if failures else ""),
          file=sys.stderr)
    print(report([out]))
    return 1 if failures else 0


# =============================================================================== report

def pct(n, d) -> str:
    return f"{n}/{d} ({100 * n / d:.0f}%)" if d else "-"


def report(files) -> str:
    runs: dict[tuple, dict] = {}
    for f in files:
        lines = [json.loads(line) for line in Path(f).read_text().splitlines() if line.strip()]
        h = lines[0]
        key = (h["model"], h["effort"], h["max_attempts"])
        run = runs.setdefault(key, {"header": h, "samples": [], "failures": 0})
        run["samples"] += [r for r in lines if r["type"] == "sample"]
        run["failures"] += sum(r["type"] == "failure" for r in lines)
    out = []
    for (model, effort, max_attempts), run in runs.items():
        samples = run["samples"]
        langs = [lang for lang in LANGS if any(s["lang"] == lang for s in samples)]
        by = {lang: [s for s in samples if s["lang"] == lang] for lang in langs}
        n_tasks = len({s["task"] for s in samples})
        per_task = max((len([s for s in samples if s["task"] == t and s["lang"] == langs[0]])
                        for t in {s["task"] for s in samples}), default=0)
        out.append(f"## {model} (effort {effort}, up to {max_attempts} attempts, "
                   f"{n_tasks} tasks x {per_task} sample(s))\n")

        def row(label, fn):
            out.append(f"| {label} | " + " | ".join(fn(by[lang]) for lang in langs) + " |")

        def solved(ss, which):
            return sum(s[which]["rejected"] is None and all(k == "correct" for k in s[which]["kinds"]) for s in ss)

        def cases(ss, kind):
            total = sum(len(s["final"]["kinds"]) for s in ss)
            return pct(sum(s["final"]["kinds"].count(kind) for s in ss), total)

        def tokens(ss, *fields):
            return sum(a["usage"].get(f, 0) for s in ss for a in s["attempts"] for f in fields)

        out.append("| | " + " | ".join(LANG_NAME[lang] for lang in langs) + " |")
        out.append("|---|" + "---|" * len(langs))
        row("Solved on the first attempt", lambda ss: pct(solved(ss, "first"), len(ss)))
        row(f"Solved within {max_attempts} attempts", lambda ss: pct(solved(ss, "final"), len(ss)))
        row("**Passed the examples, but silently wrong on hidden cases**",
            lambda ss: pct(sum(s["passed_examples"] and "wrong" in s["final"]["kinds"] for s in ss), len(ss)))
        row("Passed the examples, but failed loudly on hidden cases",
            lambda ss: pct(sum(s["passed_examples"] and "wrong" not in s["final"]["kinds"]
                               and "error" in s["final"]["kinds"] for s in ss), len(ss)))
        row("Hidden cases: correct", lambda ss: cases(ss, "correct"))
        row("Hidden cases: **wrong value, no error**", lambda ss: cases(ss, "wrong"))
        row("Hidden cases: error, trap or timeout", lambda ss: cases(ss, "error"))
        row("Hidden cases: program rejected", lambda ss: cases(ss, "rejected"))
        row("First attempt rejected (compile, signature, load)",
            lambda ss: pct(sum(bool(s["attempts"][0].get("rejected")) for s in ss), len(ss)))
        row("Mean attempts", lambda ss: f"{sum(len(s['attempts']) for s in ss) / max(1, len(ss)):.2f}")
        row("Input tokens (read from cache)", lambda ss: f"{tokens(ss, 'input_tokens', 'cache_read_input_tokens', 'cache_creation_input_tokens'):,} "
            f"({tokens(ss, 'cache_read_input_tokens'):,})")
        row("Output tokens (including thinking)", lambda ss: f"{tokens(ss, 'output_tokens'):,}")
        refused = sum(a["stop_reason"] == "refusal" for s in samples for a in s["attempts"])
        fell_back = sum(bool(a.get("fallback")) for s in samples for a in s["attempts"])
        notes = []
        if run["failures"]:
            notes.append(f"{run['failures']} conversation(s) failed with an API error and are not counted.")
        if refused or fell_back:
            notes.append(f"{refused} refused repl(ies); {fell_back} repl(ies) served by a fallback model.")
        out.append("\n" + " ".join(notes) if notes else "")

        out.append("\n### Per task\n\nEach cell: conversations solved on the first attempt -> solved within the "
                   "attempts, then how many hidden cases got a wrong value with no error.\n")
        out.append("| Task | " + " | ".join(LANG_NAME[lang] for lang in langs) + " |")
        out.append("|---|" + "---|" * len(langs))
        for t in sorted({s["task"] for s in samples}):
            cells = []
            for lang in langs:
                ss = [s for s in by[lang] if s["task"] == t]
                wrong = sum(s["final"]["kinds"].count("wrong") for s in ss)
                cells.append(f"{solved(ss, 'first')}/{len(ss)} -> {solved(ss, 'final')}/{len(ss)}"
                             + (f", **{wrong} wrong**" if wrong else ""))
            out.append(f"| {t} | " + " | ".join(cells) + " |")

        silent = [(s, f) for s in samples for f in s["final"]["fails"] if f["kind"] == "wrong"]
        if silent:
            out.append(f"\n<details><summary>Silent wrong answers ({len(silent)})</summary>\n")
            out.append("| Task | Language | Arguments | Expected | Returned |")
            out.append("|---|---|---|---|---|")
            for s, f in silent[:200]:
                cell = lambda v: "`" + short(v, 80).replace("|", "\\|") + "`"
                out.append(f"| {s['task']} | {LANG_NAME[s['lang']]} | {cell(f['args'])} | {cell(f['expect'])} | {cell(f['got'])} |")
            out.append("\n</details>")
        out.append("")
    return "\n".join(out)


def cmd_report(a) -> int:
    print(report(a.files))
    return 0


# =============================================================================== selftest

# Known-bad programs in bench/selftest/: each must produce exactly these non-correct kinds.
SELFTEST_PROGRAMS = [
    ("fnv1a_32", "fnv1a_32_no_mask.py", {"wrong"}),          # forgets mod 2^32: silently huge
    ("fnv1a_32", "fnv1a_32_u32.al", {"error"}),               # u32 multiply overflows: traps
    ("ipv4_to_int", "ipv4_to_int_int.py", {"wrong"}),        # int() accepts " 1", "+1" and "٤"
    ("rotate_left", "rotate_left_mod.al", {"error"}),         # negative index: traps
    ("top_words", "top_words_counter.py", {"wrong"}),        # Counter breaks ties by first occurrence
    ("max_drawdown", "max_drawdown_loop.al", {"error"}),      # runs out of steps
    ("balanced", "balanced_or_or.al", {"rejected"}),          # `||` is a syntax error
    ("balanced", "balanced_signature.py", {"rejected"}),      # wrong parameter name
]


def cmd_selftest(a) -> int:
    ensure_jar()
    tasks = {t.name: t for t in load_tasks()}
    failures = []

    def expect(cond, message):
        print(("  ok    " if cond else "  FAIL  ") + message)
        if not cond:
            failures.append(message)

    print("Reference solutions pass every example and hidden case:")
    for t in tasks.values():
        for lang in LANGS:
            g = grade(lang, t, t.reference[lang], t.examples + t.hidden, "all")
            bad = [f"{k}: {short(x, 120)}" for k, x in zip(g.kinds, g.got) if k != "correct"]
            expect(g.passed, f"{t.name} {LANG_NAME[lang]}" + (f" -> {g.rejected or bad[:3]}" if not g.passed else ""))

    print("Known-bad programs are graded as expected:")
    for task, file, kinds in SELFTEST_PROGRAMS:
        lang = "alang" if file.endswith(".al") else "python"
        g = grade(lang, tasks[task], (SELFTEST_DIR / file).read_text(), tasks[task].hidden, "hidden")
        seen = set(g.kinds) - {"correct"}
        expect(seen == kinds, f"{file}: {sorted(seen)} (expected {sorted(kinds)})")

    print("The feedback loop:")
    bad = {("fnv1a_32", "python"): "fnv1a_32_no_mask.py", ("balanced", "alang"): "balanced_or_or.al",
           ("ipv4_to_int", "python"): "ipv4_to_int_int.py"}

    def scripted(task, lang):
        first = (SELFTEST_DIR / bad[(task.name, lang)]).read_text()
        return [first, task.reference[lang]]

    model = ScriptedModel("scripted", scripted)
    r = solve(tasks["fnv1a_32"], "python", model, 3)
    expect(len(r["attempts"]) == 2 and "wrong" in r["first"]["kinds"] and not r["final"]["fails"],
           "a wrong first attempt gets example feedback, and the second attempt is graded")
    expect("should return `3826002220`" in r["attempts"][0]["feedback"], "the feedback names the failing example")
    r = solve(tasks["balanced"], "alang", model, 3)
    expect(r["attempts"][0]["rejected"] and "E0001" in r["attempts"][0]["feedback"],
           "a rejected ALang program gets the checker's diagnostics as feedback")
    r = solve(tasks["ipv4_to_int"], "python", model, 3)
    expect(len(r["attempts"]) == 1 and r["passed_examples"] and "wrong" in r["final"]["kinds"],
           "a program that passes the examples stops the loop and is still graded on hidden cases")
    expect(extract_code("text\n```py\nshort\n```\n```python\nlonger code\n```", "python") == "longer code\n",
           "code extraction takes the longest block tagged with the language")

    print("An end-to-end run with the reference model, and its report:")
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "run.jsonl"
        args = argparse.Namespace(tasks="mul_mod,top_words", langs="alang,python", model="reference", effort="high",
                                  no_fallbacks=False, attempts=3, samples=1, jobs=2, out=str(out), resume=None)
        with open(os.devnull, "w") as devnull, contextlib.redirect_stdout(devnull), \
                contextlib.redirect_stderr(devnull):
            code = cmd_run(args)
        text = report([out])
        expect(code == 0 and "| Solved on the first attempt | 2/2 (100%) | 2/2 (100%) |" in text,
               "`run --model reference` solves everything and `report` says so")

    print("The Claude API client, against a local mock server:")
    check_api_client(expect)

    print(f"\nselftest: {'FAILED, ' + str(len(failures)) + ' problem(s)' if failures else 'all checks passed'}")
    return 1 if failures else 0


def check_api_client(expect):
    """Sends one request through the real SDK to a local server and checks the request and the parsing."""
    try:
        import anthropic
    except ImportError:
        print("  skip  the Anthropic SDK is not installed (pip install -r bench/requirements.txt)")
        return
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    seen = {}
    text = "Here it is:\n```python\ndef f():\n    return 1\n```"
    events = [
        ("message_start", {"type": "message_start", "message": {
            "id": "msg_test", "type": "message", "role": "assistant", "model": DEFAULT_MODEL, "content": [],
            "stop_reason": None, "stop_sequence": None,
            "usage": {"input_tokens": 11, "output_tokens": 1, "cache_read_input_tokens": 5,
                      "cache_creation_input_tokens": 3}}}),
        ("content_block_start", {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
        ("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": text}}),
        ("content_block_stop", {"type": "content_block_stop", "index": 0}),
        ("message_delta", {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                           "usage": {"output_tokens": 7}}),
        ("message_stop", {"type": "message_stop"}),
    ]

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            seen["path"] = self.path
            seen["beta"] = self.headers.get("anthropic-beta")
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            seen.setdefault("first", body)
            seen["last"] = body
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            for name, data in events:
                self.wfile.write(f"event: {name}\ndata: {json.dumps(data)}\n\n".encode())

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        client = anthropic.Anthropic(base_url=f"http://127.0.0.1:{server.server_port}", api_key="test-key",
                                     max_retries=0, http_client=anthropic.DefaultHttpxClient(trust_env=False))
        model = ClaudeModel(DEFAULT_MODEL, "high", True, client=client)
        r = model.reply("the system prompt", [{"role": "user", "content": "the task"}], None, "python")
        history = [{"role": "user", "content": "the task"}, {"role": "assistant", "content": r.content},
                   {"role": "user", "content": "the feedback"}]
        model.reply("the system prompt", history, None, "python")
    except Exception as e:
        expect(False, f"the request failed: {type(e).__name__}: {e}")
        return
    finally:
        server.shutdown()
    body = seen.get("first", {})
    expect(body.get("model") == DEFAULT_MODEL and body.get("thinking") == {"type": "adaptive"}
           and body.get("output_config") == {"effort": "high"} and body.get("stream") is True,
           "the request names the model, adaptive thinking and the effort, and streams")
    expect(body.get("fallbacks") == "default" and "server-side-fallback-2026-07-01" in (seen.get("beta") or ""),
           "refusal fallbacks are requested with their beta header")
    expect(body.get("cache_control") == {"type": "ephemeral"} and body.get("system") == "the system prompt",
           "the request caches the prompt prefix")
    expect(r.text == text and extract_code(r.text, "python") == "def f():\n    return 1\n" and r.stop_reason == "end_turn",
           "the streamed reply is assembled and its code extracted")
    second = seen.get("last", {}).get("messages", [])
    expect(len(second) == 3 and second[1] == {"role": "assistant", "content": [{"type": "text", "text": text}]},
           "the reply goes back into the conversation for the feedback round")
    expect(r.usage == {"input_tokens": 11, "output_tokens": 7, "cache_read_input_tokens": 5,
                       "cache_creation_input_tokens": 3} and not r.fallback, "token usage is recorded")


# =============================================================================== main

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run", help="ask a model to solve the tasks and grade the results")
    r.add_argument("--model", default=DEFAULT_MODEL, help=f"model id, or `reference` for an offline dry run (default {DEFAULT_MODEL})")
    r.add_argument("--effort", default="high", choices=["low", "medium", "high", "xhigh", "max", "default"],
                   help="output_config.effort; `default` leaves it to the model (default high)")
    r.add_argument("--attempts", type=int, default=3, help="replies per conversation, including feedback rounds (default 3)")
    r.add_argument("--samples", type=int, default=1, help="conversations per task and language (default 1)")
    r.add_argument("--tasks", help="comma-separated task names (default: all)")
    r.add_argument("--langs", default="alang,python", help="comma-separated: alang,python (default both)")
    r.add_argument("--jobs", type=int, default=4, help="conversations in parallel (default 4)")
    r.add_argument("--no-fallbacks", action="store_true", help="do not let a refused request fall back to another model")
    r.add_argument("--out", help="results file (default bench/results/<time>-<model>.jsonl)")
    r.add_argument("--resume", metavar="FILE", help="continue an interrupted run, skipping finished conversations")
    r.set_defaults(fn=cmd_run)
    rep = sub.add_parser("report", help="summarize result files as Markdown")
    rep.add_argument("files", nargs="+")
    rep.set_defaults(fn=cmd_report)
    st = sub.add_parser("selftest", help="check tasks, grader and feedback loop offline")
    st.set_defaults(fn=cmd_selftest)
    a = p.parse_args()
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
