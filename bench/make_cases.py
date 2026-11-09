#!/usr/bin/env python3
"""Builds bench/tasks/*/cases.json: the visible examples and the hidden test cases of every task.

Hand-picked edge cases are listed below for each task, plus a few seeded random cases. Expected values
come from reference.py; reference.al must agree on every stored case and on extra random inputs, or
nothing is written. Usage: python3 bench/make_cases.py [TASK...]  (needs target/alang.jar)
"""
import importlib.util
import json
import os
import random
import string
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TASKS = ROOT / "bench" / "tasks"
JAR = ROOT / "target" / "alang.jar"
ONLY = sys.argv[1:]

I64_MIN, I64_MAX = -(2**63), 2**63 - 1


def canon(v):
    return json.dumps(v, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def dump_cases(task, examples, hidden):
    """JSON with one case per line, so diffs and reviews stay readable."""
    def block(cases):
        return ",\n".join("    " + json.dumps(c, ensure_ascii=False) for c in cases)
    return (f'{{\n  "tool": {json.dumps(task)},\n  "examples": [\n{block(examples)}\n  ],\n'
            f'  "hidden": [\n{block(hidden)}\n  ]\n}}\n')


def load_py(task):
    spec = importlib.util.spec_from_file_location(f"ref_{task}", TASKS / task / "reference.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return getattr(mod, task)


def run_al(task, args):
    env = dict(os.environ, JAVA_TOOL_OPTIONS="")
    p = subprocess.run(["java", "-jar", str(JAR), "call", str(TASKS / task / "reference.al"), task],
                       input=json.dumps(args).encode(), capture_output=True, env=env, timeout=60)
    return json.loads(p.stdout)


# ----------------------------------------------------------------------------- inputs per task

def luhn_fix(digits):
    """Replace the last digit so the string passes the Luhn check."""
    body = digits[:-1]
    for d in "0123456789":
        cand = body + d
        total = 0
        for k, c in enumerate(reversed(cand)):
            v = int(c)
            if k % 2:
                v *= 2
                if v > 9:
                    v -= 9
            total += v
        if total % 10 == 0:
            return cand
    raise AssertionError


def g_luhn(r):
    s = "".join(r.choice(string.digits) for _ in range(r.randint(2, 32)))
    return {"number": luhn_fix(s) if r.random() < 0.5 else s}


def roman(n):
    out = ""
    for v, s in [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"), (50, "L"),
                 (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]:
        while n >= v:
            out += s
            n -= v
    return out


def g_text(r, alphabet, lo, hi):
    return "".join(r.choice(alphabet) for _ in range(r.randint(lo, hi)))


def g_interval(r, span=10**9):
    a, b = r.randint(-span, span), r.randint(-span, span)
    return {"start": min(a, b), "end": max(a, b)}


def g_small_interval(r):
    a = r.randint(-20, 20)
    return {"start": a, "end": a + r.randint(0, 6)}


def g_duration(r):
    parts = []
    for unit in "hms":
        if r.random() < 0.6:
            parts.append(str(r.randint(0, 999999)).zfill(r.randint(1, 3)) + unit)
    text = "".join(parts) or "0s"
    if r.random() < 0.3:  # corrupt it
        i = r.randrange(len(text) + 1)
        text = text[:i] + r.choice("hms .+-x0") + text[i:]
    return {"text": text[:32]}


def g_version(r):
    return ".".join(str(r.randint(0, 12)).zfill(r.randint(1, 2)) for _ in range(r.randint(1, 8)))


def g_ip(r):
    parts = [str(r.randint(0, 255)) for _ in range(4)]
    text = ".".join(parts)
    if r.random() < 0.4:
        i = r.randrange(len(text) + 1)
        text = text[:i] + r.choice(".0 9a-+") + text[i:]
    return {"text": text}


def g_words(r, letters, max_len, max_bytes):
    words = []
    while True:
        w = "".join(r.choice(letters) for _ in range(r.randint(1, max_len)))
        if len(" ".join(words + [w])) > max_bytes:
            break
        words.append(w)
        if r.random() < 0.08:
            break
    return " ".join(words)


def g_top(r):
    vocab = ["".join(r.choice("abcdef") for _ in range(r.randint(1, 4))) for _ in range(10)]
    seps = [" ", " ", ", ", ". ", "-", "1", "!? "]
    out = ""
    while True:
        piece = r.choice(vocab) + r.choice(seps)
        if len(out + piece) > 256:
            break
        out += piece
        if r.random() < 0.05:
            break
    return {"text": out, "k": r.randint(1, 8)}


def g_grid(r):
    rows, cols = r.randint(1, 8), r.randint(1, 8)
    density = r.choice([0.0, 0.2, 0.3, 0.4])
    grid = ["".join("#" if r.random() < density else "." for _ in range(cols)) for _ in range(rows)]
    if r.random() < 0.8:  # usually keep start and goal open
        grid[0] = "." + grid[0][1:]
        grid[-1] = grid[-1][:-1] + "."
    return {"grid": grid}


def g_date(r):
    from datetime import date, timedelta
    lo, hi = date(1, 1, 1).toordinal(), date(9999, 12, 31).toordinal()
    if r.random() < 0.5:
        a = r.randint(lo, hi)
        b = r.randint(lo, hi)
    else:
        a = r.randint(lo, hi - 4000)
        b = a + r.randint(-1500, 1500)
        b = max(lo, min(hi, b))
    d1, d2 = date.fromordinal(a), date.fromordinal(b)
    return {"y1": d1.year, "m1": d1.month, "d1": d1.day, "y2": d2.year, "m2": d2.month, "d2": d2.day}


def g_unicode_text(r):
    pool = list(string.ascii_letters + string.digits + " .,") + ["é", "ß", "日", "本", "😀", "Ж", "ü"]
    out = ""
    while True:
        c = r.choice(pool)
        if len((out + c).encode()) > 64:
            break
        out += c
        if r.random() < 0.06:
            break
    return {"text": out}


INPUTS = {
    "luhn_valid": dict(
        examples=[{"number": "79927398713"}, {"number": "79927398710"}, {"number": "7"}],
        hidden=[{"number": s} for s in [
            "", "0", "00", "18", "4111111111111111", "4111 1111 1111 1111", "4539578763621486",
            "1234567812345670", "1234567812345678", "-18", "+18", "18\n", "١٨", "a1",
            "00000000000000000000000000000000", luhn_fix("98765432109876543210987654321098")]],
        gen=g_luhn),
    "roman_to_int": dict(
        examples=[{"s": "III"}, {"s": "MCMXCIV"}, {"s": "XLII"}],
        hidden=[{"s": roman(n)} for n in [1, 4, 9, 14, 40, 90, 400, 900, 444, 999, 1666, 2024, 3888, 3999]],
        gen=lambda r: {"s": roman(r.randint(1, 3999))}),
    "int_to_roman": dict(
        examples=[{"n": 3}, {"n": 1994}, {"n": 58}],
        hidden=[{"n": n} for n in [1, 4, 9, 14, 40, 90, 400, 900, 444, 999, 1000, 2024, 3888, 3999]],
        gen=lambda r: {"n": r.randint(1, 3999)}),
    "rle_encode": dict(
        examples=[{"text": "aaabcc"}, {"text": "abbbbbbbbbbbb"}, {"text": ""}],
        hidden=[{"text": t} for t in [
            "a", "ab", "aa", "z" * 64, "  ", "a a", "!!!??", "aabbaa", "xyz", "abcdefghij" * 6,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaab", "..--..", "AaAa"]],
        gen=lambda r: {"text": g_text(r, "aab b.!", 0, 64)}),
    "balanced": dict(
        examples=[{"text": "([]{})"}, {"text": "([)]"}, {"text": "no brackets here"}],
        hidden=[{"text": t} for t in [
            "", "(", ")", ")(", "((()))", "{[()()]}", "{[(])}", "a(b[c]d)e", "(" * 64 + ")" * 64,
            "(((((((((((", "}", "[]]", "[[]", "f(x) = {a[0]}", "<>"]],
        gen=lambda r: {"text": g_text(r, "()[]{}()x", 0, 40)}),
    "merge_intervals": dict(
        examples=[
            {"intervals": [{"start": 1, "end": 3}, {"start": 2, "end": 6}, {"start": 8, "end": 10},
                           {"start": 15, "end": 18}]},
            {"intervals": [{"start": 1, "end": 4}, {"start": 4, "end": 5}]},
            {"intervals": [{"start": 5, "end": 6}, {"start": 1, "end": 2}]}],
        hidden=[
            {"intervals": []},
            {"intervals": [{"start": 7, "end": 7}]},
            {"intervals": [{"start": 1, "end": 10}, {"start": 2, "end": 3}]},
            {"intervals": [{"start": 1, "end": 2}, {"start": 3, "end": 4}]},
            {"intervals": [{"start": 3, "end": 5}, {"start": 1, "end": 3}]},
            {"intervals": [{"start": -10, "end": -5}, {"start": -6, "end": 0}, {"start": 1, "end": 1}]},
            {"intervals": [{"start": 2, "end": 4}, {"start": 2, "end": 9}, {"start": 2, "end": 3}]},
            {"intervals": [{"start": -10**9, "end": 10**9}, {"start": 0, "end": 0}]},
            {"intervals": [{"start": 4, "end": 4}, {"start": 4, "end": 4}, {"start": 4, "end": 4}]},
            {"intervals": [{"start": 10 * i, "end": 10 * i + 5} for i in range(16)][::-1]},
            {"intervals": [{"start": i, "end": i + 1} for i in range(16)]},
        ],
        gen=lambda r: {"intervals": [(g_small_interval if r.random() < 0.7 else g_interval)(r)
                                     for _ in range(r.randint(0, 16))]}),
    "insertion_point": dict(
        examples=[{"xs": [1, 3, 3, 7], "target": 3}, {"xs": [1, 3, 3, 7], "target": 4},
                  {"xs": [], "target": 5}],
        hidden=[
            {"xs": [1, 3, 3, 7], "target": 9}, {"xs": [1, 3, 3, 7], "target": 0},
            {"xs": [5, 5, 5, 5], "target": 5}, {"xs": [5], "target": 5}, {"xs": [5], "target": 6},
            {"xs": [I64_MIN, 0, I64_MAX], "target": I64_MAX}, {"xs": [I64_MIN, 0, I64_MAX], "target": I64_MIN},
            {"xs": [I64_MIN, I64_MIN], "target": I64_MAX}, {"xs": list(range(32)), "target": 31},
            {"xs": list(range(32)), "target": 32}, {"xs": [2] * 32, "target": 2}, {"xs": [2] * 32, "target": 3},
        ],
        gen=lambda r: (lambda xs: {"xs": xs, "target": r.choice(xs + [r.randint(-50, 50)]) if xs else 0})(
            sorted(r.randint(-50, 50) for _ in range(r.randint(0, 32))))),
    "mul_mod": dict(
        examples=[{"a": 3, "b": 4, "m": 5}, {"a": 123456789, "b": 987654321, "m": 1000000007},
                  {"a": 10**15, "b": 10**15 + 7, "m": 10**18 + 9}],
        hidden=[
            {"a": 0, "b": 0, "m": 1}, {"a": 0, "b": 5, "m": 7}, {"a": 6, "b": 6, "m": 7},
            {"a": 2**62 - 1, "b": 2**62 - 1, "m": 2**62}, {"a": 2**62 - 1, "b": 2**62 - 2, "m": 2**62},
            {"a": 2**61, "b": 3, "m": 2**62}, {"a": 4294967296, "b": 4294967296, "m": 2**62 - 57},
            {"a": 999999999999, "b": 999999999999, "m": 10**12},
            {"a": 3037000500, "b": 3037000500, "m": 9223372036854775807 // 2},
        ],
        gen=lambda r: (lambda m: {"a": r.randrange(m), "b": r.randrange(m), "m": m})(
            r.choice([r.randint(1, 1000), r.randint(1, 2**31), r.randint(1, 2**62)]))),
    "parse_duration": dict(
        examples=[{"text": "1h30m"}, {"text": "45s"}, {"text": "1h1h"}],
        hidden=[{"text": t} for t in [
            "2h", "90m", "1h2m3s", "0s", "000001s", "999999h", "1234567s", "", "h", "10", "1H", "1h 30m",
            " 1h", "1m1h", "1h1s", "1s1m", "1h30m0s", "-5s", "+5s", "1.5h", "5ss", "٥s", "5s\n", "00h00m00s"]],
        gen=g_duration),
    "compare_versions": dict(
        examples=[{"a": "1.10.2", "b": "1.9"}, {"a": "1.0", "b": "1"}, {"a": "2.0.1", "b": "2.1"}],
        hidden=[{"a": a, "b": b} for a, b in [
            ("1.01", "1.1"), ("1", "1.0.0.0.0.0.0.0"), ("0.0.1", "0"), ("999999999.1", "999999999.0"),
            ("10", "9"), ("1.2.3.4.5.6.7.8", "1.2.3.4.5.6.7.9"), ("3", "3"), ("0", "0.0"),
            ("2.10", "2.9.9"), ("000000001", "1"), ("1.0.10", "1.0.9")]],
        gen=lambda r: {"a": g_version(r), "b": g_version(r)}),
    "ipv4_to_int": dict(
        examples=[{"text": "192.168.0.1"}, {"text": "255.255.255.255"}, {"text": "256.1.1.1"}],
        hidden=[{"text": t} for t in [
            "0.0.0.0", "1.2.3.4", "10.0.0.1", "127.0.0.1", "01.2.3.4", "00.1.1.1", "0.0.0.00", "1.2.3",
            "1.2.3.4.5", "1..2.3", "1.2.3.4.", ".1.2.3", " 1.2.3.4", "1.2.3.4 ", "+1.2.3.4", "1.2.3.-4",
            "1000.1.1.1", "1.2.3.٤", "1.2.3.4\n", "a.b.c.d", "", "255.255.255.256"]],
        gen=g_ip),
    "word_wrap": dict(
        examples=[{"text": "the quick brown fox jumps over the lazy dog", "width": 10},
                  {"text": "a bb ccc", "width": 3}, {"text": "", "width": 5}],
        hidden=[
            {"text": "a well-known state-of-the-art tool", "width": 10},
            {"text": "use a pre-built read-only cache", "width": 12},
            {"text": "supercalifragilistic is long", "width": 5},
            {"text": "a b c", "width": 1}, {"text": "abc", "width": 3}, {"text": "abc def", "width": 7},
            {"text": "abc def", "width": 6}, {"text": " ".join(["word"] * 51), "width": 80},
            {"text": "x " * 127 + "y", "width": 1}, {"text": "one", "width": 80},
        ],
        gen=lambda r: {"text": g_words(r, "abcde-", 9, 256), "width": r.randint(1, 30)}),
    "top_words": dict(
        examples=[{"text": "the cat and the hat", "k": 2}, {"text": "b a c b a", "k": 3},
                  {"text": "one, two; two... three! three three", "k": 8}],
        hidden=[
            {"text": "zeta alpha zeta alpha mid", "k": 2}, {"text": "zeta alpha zeta alpha mid", "k": 3},
            {"text": "ab abc ab abc", "k": 2}, {"text": "", "k": 3}, {"text": "?!? 42 ...", "k": 1},
            {"text": "a1b2c3a", "k": 8}, {"text": "x y z", "k": 1},
            {"text": "dog cat dog bird cat dog fish", "k": 8}, {"text": "same same same", "k": 8},
            {"text": "c b a", "k": 8}, {"text": "q-r q_r q.r", "k": 2},
        ],
        gen=g_top),
    "grid_path": dict(
        examples=[{"grid": ["..", ".."]}, {"grid": [".#", "#."]}, {"grid": ["."]}],
        hidden=[
            {"grid": ["#"]}, {"grid": ["...", "##.", "..."]}, {"grid": ["." * 8] * 8},
            {"grid": ["........"]}, {"grid": ["."] * 8},
            {"grid": [".#......", ".#.####.", ".#.#..#.", ".#.#.##.", ".#.#....", ".#.####.", ".#......", "...#####"]},
            {"grid": ["#.", ".."]}, {"grid": ["..", ".#"]},
            {"grid": ["....#", "###.#", "....#", ".####", "....."]},
            {"grid": [".#.", ".#.", "..."]},
        ],
        gen=g_grid),
    "days_between": dict(
        examples=[{"y1": 2024, "m1": 1, "d1": 1, "y2": 2024, "m2": 3, "d2": 1},
                  {"y1": 2000, "m1": 2, "d1": 28, "y2": 2000, "m2": 3, "d2": 1},
                  {"y1": 2023, "m1": 12, "d1": 31, "y2": 2023, "m2": 1, "d2": 1}],
        hidden=[{"y1": a, "m1": b, "d1": c, "y2": d, "m2": e, "d2": f} for a, b, c, d, e, f in [
            (1900, 2, 28, 1900, 3, 1), (1, 1, 1, 9999, 12, 31), (2024, 2, 29, 2024, 2, 29),
            (2100, 2, 28, 2100, 3, 1), (2000, 1, 1, 2001, 1, 1), (1999, 1, 1, 2000, 1, 1),
            (9999, 12, 31, 1, 1, 1), (1600, 2, 29, 1700, 2, 28), (4, 2, 29, 5, 3, 1),
            (1970, 1, 1, 2038, 1, 19)]],
        gen=g_date),
    "max_drawdown": dict(
        examples=[{"prices": [5, 3, 8, 1]}, {"prices": [1, 2, 3]}, {"prices": []}],
        hidden=[{"prices": p} for p in [
            [7], [10, 1], [3, 3, 3], [1, 10**12, 0], [5, 4, 3, 2, 1], [2, 9, 1, 8, 0], [0, 0],
            [10**12, 0, 10**12], list(range(32)), list(range(32, 0, -1))]],
        gen=lambda r: {"prices": [r.randint(0, r.choice([20, 10**12])) for _ in range(r.randint(0, 32))]}),
    "rotate_left": dict(
        examples=[{"xs": [1, 2, 3, 4, 5], "k": 2}, {"xs": [1, 2, 3, 4, 5], "k": -1}, {"xs": [], "k": 3}],
        hidden=[
            {"xs": [1, 2, 3, 4, 5], "k": 0}, {"xs": [1, 2, 3, 4, 5], "k": 5}, {"xs": [1, 2, 3, 4, 5], "k": 7},
            {"xs": [1, 2, 3, 4, 5], "k": -7}, {"xs": [1, 2, 3], "k": 10**9}, {"xs": [1, 2, 3], "k": -10**9},
            {"xs": [42], "k": -3}, {"xs": [1, 2], "k": -1}, {"xs": list(range(32)), "k": -33},
            {"xs": [], "k": -10**9},
        ],
        gen=lambda r: {"xs": [r.randint(-9, 9) for _ in range(r.randint(0, 32))],
                       "k": r.choice([r.randint(-40, 40), r.randint(-10**9, 10**9)])}),
    "fnv1a_32": dict(
        examples=[{"text": ""}, {"text": "a"}, {"text": "foobar"}],
        hidden=[{"text": t} for t in [
            "hello", "é", "日本", "😀", "naïve café", "x" * 64, "The quick brown fox", "\u0000",
            "Ünïcödé ✓"]],
        gen=g_unicode_text),
    "format_thousands": dict(
        examples=[{"n": 1234567}, {"n": -1234}, {"n": 999}],
        hidden=[{"n": n} for n in [
            0, 1000, -1000, 100, -999, 1000000, 12345, -1, I64_MAX, I64_MIN, I64_MIN + 1, -100000]],
        gen=lambda r: {"n": r.choice([r.randint(-10**6, 10**6), r.randint(I64_MIN, I64_MAX)])}),
    "levenshtein": dict(
        examples=[{"a": "kitten", "b": "sitting"}, {"a": "", "b": "abc"}, {"a": "flaw", "b": "lawn"}],
        hidden=[{"a": a, "b": b} for a, b in [
            ("same", "same"), ("a", "b"), ("abc", ""), ("intention", "execution"), ("", ""),
            ("a" * 32, "b" * 32), ("abcdefghijklmnopqrstuvwxyz012345", "bcdefghijklmnopqrstuvwxyz0123456"),
            ("ab", "ba"), ("Saturday", "Sunday")]],
        gen=lambda r: {"a": g_text(r, "abc", 0, 32), "b": g_text(r, "abc", 0, 32)}),
}

HIDDEN_RANDOM = 6     # random cases added to each task's stored hidden set
CROSSCHECK = 40       # extra random inputs only used to cross-check the references


def build(task):
    spec = INPUTS[task]
    r = random.Random(f"alang-bench/{task}")
    ref = load_py(task)
    hidden = list(spec["hidden"]) + [spec["gen"](r) for _ in range(HIDDEN_RANDOM)]
    extra = [spec["gen"](r) for _ in range(CROSSCHECK)]

    def expect(args):
        return json.loads(json.dumps(ref(**json.loads(json.dumps(args)))))

    examples = [{"args": a, "expect": expect(a)} for a in spec["examples"]]
    hidden_cases = [{"args": a, "expect": expect(a)} for a in hidden]
    checks = examples + hidden_cases + [{"args": a, "expect": expect(a)} for a in extra]
    with ThreadPoolExecutor(4) as pool:
        outs = list(pool.map(lambda c: run_al(task, c["args"]), checks))
    bad = 0
    for c, o in zip(checks, outs):
        if not o.get("ok") or canon(o["result"]) != canon(c["expect"]):
            bad += 1
            print(f"  MISMATCH {task}: {canon(c['args'])}\n    python: {canon(c['expect'])}\n    alang:  {canon(o)}")
    if not bad:
        (TASKS / task / "cases.json").write_text(dump_cases(task, examples, hidden_cases))
    print(f"{task}: {len(examples)} examples, {len(hidden_cases)} hidden, {len(checks)} cross-checked, {bad} mismatches")
    return bad


total = sum(build(t) for t in sorted(INPUTS) if not ONLY or t in ONLY)
print("mismatches:", total)
sys.exit(1 if total else 0)
