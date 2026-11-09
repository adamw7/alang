# ALang compared with other languages

ALang is not trying to beat Python, Rust or Go at what they are for. It is built for a job those
languages were not designed for: **code that an AI agent writes and that someone then runs
without reading it first.** For that job you want four answers before the code runs:

1. Will it finish?
2. How much memory can it use?
3. Will it give the same answer next time, on another machine?
4. When it is wrong, will it say so, and say how to fix it?

For mainstream languages the honest answer to most of these is "it depends". In ALang the
compiler enforces a rule for each one. This page shows the difference with small programs you can run yourself.

> Every ALang program on this page compiles and passes its tests, and every ALang diagnostic and
> trap shown is the toolchain's exact output. `DocsTest` checks both on every build. Output from
> other languages was recorded on x86-64 Linux with Python 3.13, Node.js 22, Go 1.24, Rust 1.97,
> GCC 13.3, Clang 18 and Java 21.

## At a glance

| | ALang | Python | JavaScript | Go | Rust | C |
|---|---|---|---|---|---|---|
| Integer overflow | traps (R0001) | integers grow until memory runs out | silent precision loss above 2^53 | wraps silently | panics in debug builds, wraps in release builds | undefined behavior |
| Index out of range | traps (R0003) | exception, but negative indexes silently read from the end | `undefined` | panics | panics | undefined behavior |
| `null` | none: absence is an enum | `None` | `null` and `undefined` | `nil` | none (`Option`) | `NULL` |
| Heap | none | garbage-collected | garbage-collected | garbage-collected | `Box`, `Vec`, ... | `malloc` |
| Worst-case memory known before running | yes, printed by `alang check` | no | no | no | no | no |
| Guaranteed to terminate | yes: no recursion, every `while` has a `limit` | no | no | no | no | no |
| Same output on every run and machine | guaranteed and tested | no: `set` order changes per process | no: `Math.random`, `Date`, engine-specific math | no: map order is randomized | no: `HashMap` order is randomized | no: undefined behavior, FMA contraction, locale |
| Can reach files, network, clock | no | yes | yes | yes | yes | yes |
| Tool schema for an agent | generated from the signature | written by hand or with a library | written by hand or with a library | written by hand or with a library | written by hand or with a library | written by hand |

The sections below show each row in action.

## 1. Arithmetic never goes wrong quietly

Computing a midpoint as `(lo + hi) / 2` is a classic bug. It sat in the JDK's
`Arrays.binarySearch` for about nine years before Joshua Bloch reported it in 2006. Here it is in
ALang:

```alang-traps midpoint.al
fn midpoint(lo: i32, hi: i32) -> i32 {
    return (lo + hi) / 2;
}

fn main() {
    print(midpoint(2_000_000_000, 2_100_000_000));
}
```

```text
trap[R0001]: integer overflow: result of `+` does not fit in i32
 --> midpoint.al:2:13
  |
2 |     return (lo + hi) / 2;
  |             ^^^^^^^
  in fn midpoint (called at 6:11)
  in fn main (entry point, line 5)
  = hint: An arithmetic result does not fit its type. ALang never wraps silently. Use a wider type, or check before computing.
```

The same function with 32-bit integers in other languages:

| Language | `midpoint(2000000000, 2100000000)` |
|----------|------------------------------------|
| C (`gcc -O2`) | `-97483648`. Signed overflow is undefined behavior; this result is just what happened this time. |
| Java | `-97483648` |
| Go (`int32`) | `-97483648` |
| Rust, release build | `-97483648` |
| Rust, debug build | panics with `attempt to add with overflow` |
| **ALang** | **trap R0001 at `midpoint.al:2:13`, on every run, in the interpreter and in compiled C** |

JavaScript and Python get this case right, but they have their own problems. JavaScript numbers are
doubles, so `9007199254740993` silently becomes `9007199254740992`. Python integers never overflow,
so a single expression can grow until it uses all available memory.

An agent reading the ALang trap knows the operator, the line, the column and the call path, and
the hint tells it the fix. Here the fix is a wider type:

```alang
fn midpoint(lo: i32, hi: i32) -> i32 {
    return i32((i64(lo) + i64(hi)) / 2);
}

test "midpoint of large values" {
    assert midpoint(2_000_000_000, 2_100_000_000) == 2_050_000_000;
    assert midpoint(-2_000_000_000, 2_100_000_000) == 50_000_000;
}
```

The program compiled to C traps the same way, with the same code, message and location:

```
$ bin/alang build midpoint.al -o midpoint.c && cc -std=c99 -O2 midpoint.c -lm -o midpoint && ./midpoint
trap[R0001]: integer overflow: result of `+` does not fit in i32
  --> midpoint.al:2:13
```

Out-of-range indexes (R0003), division by zero (R0002), lossy conversions such as `u8(300)`
(R0009) and invalid shifts (R0012) work the same way: there is no undefined behavior, no silent
wraparound, and Python's `xs[i - 1]` with `i == 0` cannot quietly read the last element.

## 2. The same answer on every run and every machine

**Iteration order.** In Python, the order of a `set` of strings changes from one process to the
next:

```
$ python3 -c 'print(list({"apple", "banana", "cherry", "date"}))'   # three runs
['apple', 'date', 'cherry', 'banana']
['apple', 'date', 'banana', 'cherry']
['banana', 'cherry', 'apple', 'date']
```

Go randomizes map iteration on purpose, and Rust's `HashMap` is seeded randomly. A test that
passes on one run fails on the next. ALang has no hash-ordered collections: lists keep
their order, and `for x in xs` iterates over a snapshot.

**Floating point.** The same C source, compiled by the same compiler, gives two answers depending
on a target flag, because the compiler may fuse `a * b + c` into one fused multiply-add instruction:

```c
#include <stdio.h>

double mul_add(double a, double b, double c) { return a * b + c; }

int main(void) {
    volatile double a = 0.1, b = 10.0, c = -1.0;
    printf("%g\n", mul_add(a, b, c));
    return 0;
}
```

```
$ gcc -O2 fma.c && ./a.out
0
$ gcc -O2 -march=haswell fma.c && ./a.out
5.55112e-17
```

In ALang, `a * b + c` always rounds twice. The C backend turns contraction off and refuses to
compile with `-ffast-math` or x87 excess precision. The test suite compares the C output byte for
byte with the interpreter under gcc and clang at `-O0` to `-O3`, including with
`-ffp-contract=fast` forced on the command line.

```alang
fn mul_add(a: f64, b: f64, c: f64) -> f64 {
    return a * b + c;
}

test "a * b + c rounds twice on every compiler and CPU" {
    assert mul_add(0.1, 10.0, -1.0) == 0.0;
}
```

`sin`, `exp` and `pow` are left out on purpose: their last bits differ between math libraries.
ALang only has operations that IEEE 754 requires to be correctly rounded, so every platform
produces the same bits.

**Locale.** `String.format("%.2f", 3.14159)` prints `3.14` in Java, or `3,14` when the JVM runs
with a German locale. C's `printf` behaves the same way after `setlocale`. ALang formats every
number itself, and the test suite runs every program under `de-DE`, `tr-TR` and locales with
Arabic and Devanagari digits, and checks that the output is identical every time.

**No hidden inputs.** ALang has no clock, no random numbers, no environment variables, no threads
and no I/O except `print`. A program's output depends only on its source code and its tool
arguments. If a task needs randomness, take a seed as an argument.

## 3. Programs that always finish

Recursion is the usual way an agent's program runs out of stack. In Python that happens at run
time (`RecursionError` at a depth of about 1000). In C, Rust and Go the program crashes, at a depth
that depends on the stack size, the frame size and the optimization level. In ALang it is a
compile error:

```alang-rejected fact.al
fn fact(n: u64) -> u64 {
    if n == 0 {
        return 1;
    }
    return n * fact(n - 1);
}
```

```text
error[E0401]: recursion is not allowed: fact -> fact
 --> fact.al:1:4
  |
1 | fn fact(n: u64) -> u64 {
  |    ^^^^
  = hint: rewrite with a loop; keep pending work in a fixed-capacity list (a stack) if needed
```

```alang
fn factorial(n: u64) -> u64 {
    var acc: u64 = 1;
    for i in 2..n + 1 {
        acc *= i;
    }
    return acc;
}

test "20! fits in u64" {
    assert factorial(20) == 2_432_902_008_176_640_000;
}
```

A `while` loop is the other way a program runs forever. Every `while` in ALang has to say how many
iterations it may run:

```alang-rejected collatz.al
fn collatz_steps(start: u64) -> i64 {
    var n: u64 = start;
    var steps: i64 = 0;
    while n != 1 {
        if n % 2 == 0 {
            n = n / 2;
        } else {
            n = 3 * n + 1;
        }
        steps += 1;
    }
    return steps;
}
```

```text
error[E0001]: unexpected '{', expected 'limit' or an operator
 --> collatz.al:4:18
  |
4 |     while n != 1 {
  |                  ^
  = hint: while loops must state an iteration limit: while cond limit 1000 { ... }
```

```alang
fn collatz_steps(start: u64) -> i64
    requires start > 0
{
    var n: u64 = start;
    var steps: i64 = 0;
    while n != 1 limit 1000 {
        if n % 2 == 0 {
            n = n / 2;
        } else {
            n = 3 * n + 1;
        }
        steps += 1;
    }
    return steps;
}

test "27 takes 111 steps" {
    assert collatz_steps(27) == 111;
}
```

If the limit is too small, the loop traps with R0008 instead of hanging, and `3 * n + 1` traps
with R0001 instead of wrapping. On top of that, the interpreter counts executed statements
(`--max-steps`), so even a long program stops after exactly the same amount of work on every run.
The budget counts work, not seconds.

## 4. Memory you can read before running

Every ALang type has a size known at compile time (`str[64]`, `list[Item; 16]`, `[f64; 3]`), there
is no heap, and there is no recursion. So the checker can compute a worst-case bound on the memory of
every entry point, counting its variables and the temporary values its statements build:

```
$ bin/alang check examples/maze_bfs.al --memory
ok: examples/maze_bfs.al: 3 function(s), 1 test(s), 0 warning(s)
memory: heap 0 B, static 96 B, worst-case stack 2234 B
  routine                                     frame (B)    stack (B)
  fn find                                            61           61
  fn shortest_path                                 2089         2150
  fn main                                            84         2234  (entry)
  test "finds the shortest path"                      0         2150  (entry)
```

A program whose bound exceeds the budget does not compile (E0402, or E0405 for constants). The
budget is 8 MiB unless `--max-stack` sets another: `--max-stack 1024` suits embedded targets or a
host that gives each tool call a fixed budget. In Python, JavaScript, Go and Java,
memory use is only known after the program runs. In C and Rust a stack overflow or failed
allocation shows up at run time, and bounding memory takes external analysis tools.

## 5. No aliasing surprises

In ALang, assignment, parameters, return values and fields all copy. Two variables never share
storage. A classic Python trap shows why that matters:

```
$ python3 -c 'grid = [[0] * 3] * 3; grid[0][0] = 1; print(grid)'
[[1, 0, 0], [1, 0, 0], [1, 0, 0]]
```

```alang
fn main() {
    var grid: [[i64; 3]; 3] = [[0; 3]; 3];
    grid[0][0] = 1;
    print(grid); // [[1, 0, 0], [0, 0, 0], [0, 0, 0]]
}

test "rows are independent values" {
    var grid: [[i64; 3]; 3] = [[0; 3]; 3];
    grid[0][0] = 1;
    assert grid[1][0] == 0;

    var copy: [[i64; 3]; 3] = grid;
    copy[2][2] = 9;
    assert grid[2][2] == 0;
}
```

When a function does need to change its caller's data, both sides say so with `mut`, so a reader
sees every mutation at the call site:

```alang
fn add_tax(price_cents: mut i64, rate_percent: i64) {
    price_cents += price_cents * rate_percent / 100;
}

test "only arguments marked mut change" {
    var price: i64 = 1000;
    let rate: i64 = 20;
    add_tax(mut price, rate);
    assert price == 1200;
    assert rate == 20;
}
```

`mut` references cannot be stored or returned, so they can never dangle. That is the guarantee
Rust's references give, without lifetimes or a borrow checker to satisfy. A variable passed as
`mut` cannot appear twice in the same call (`append(mut s, s)` is E0208), and a call with a `mut`
argument is its own statement (E0215), so the order of side effects is always visible in the code.

## 6. One way to write it, and errors that say which way

Operator precedence is a common source of bugs that look correct. In C and JavaScript,
`flags & MASK == 0` means `flags & (MASK == 0)`:

```
$ node -e 'const MASK = 4; const isClear = (f) => f & MASK == 0; console.log(isClear(2))'
0
$ node -e 'const x = 50; console.log(0 < x < 10)'
true
```

JavaScript runs both without complaint. C has the same rules, and GCC warns about them only when
`-Wall` is on. ALang keeps ordinary arithmetic precedence and refuses to guess everywhere else:

```alang-rejected flags.al
const MASK: u32 = 4;

fn is_clear(flags: u32) -> bool {
    return flags & MASK == 0;
}
```

```text
error[E0001]: unexpected '==', expected one of '[', ';', an operator
 --> flags.al:4:25
  |
4 |     return flags & MASK == 0;
  |                         ^^
  = hint: comparisons do not chain and bitwise expressions must be parenthesized before comparing: write a < b and b < c, or (flags & MASK) == 0
```

```alang
const MASK: u32 = 4;

fn is_clear(flags: u32) -> bool {
    return (flags & MASK) == 0;
}

test "bit 2" {
    assert is_clear(2);
    assert not is_clear(6);
}
```

Agents learn mostly from Python, JavaScript, Rust and C, and carry those habits over. ALang
recognizes the common ones and answers with the ALang spelling:

| An agent writes | ALang answers |
|-----------------|---------------|
| `if ready && !done` | E0001: use the keyword `and` instead of `&&` |
| `let x = 5;` | E0001: every variable needs an explicit type: `let x: i32 = 0;` |
| `i++;` | E0001: there is no `++` operator; write `x += 1;` |
| `Color::Red` | E0001: enum variants are accessed with a dot: `Color.Red` |
| `Point { x: 1, y: 2 }` | E0001: struct values are built with named arguments: `Point(field: value, ...)` |
| `x > 0 ? 1 : 2` | E0001: there is no `?:` operator; use if/else or match |
| `a and b or c` | E0001: `and` and `or` cannot be mixed without parentheses: `(a and b) or c` |
| `xs.len()` | E0102: there are no methods; pass the value as the first argument: `len(xs)` |
| `let x: i64 = null;` | E0101: ALang has no null; model absence with an enum |
| `if count { }` | E0211: there is no truthiness; compare explicitly, e.g. `x != 0` |
| `10.0 / n` with `n: i64` | E0201: convert one side explicitly, e.g. `f64(n)` |
| a `match` that forgets a variant | E0306: match is not exhaustive: missing `Color.Blue` |
| a function that can fall off the end | E0301: add `return <i64>;` at the end of the function |
| an inner `let x` that reuses a name | E0105: ALang has no shadowing; choose a new name |

Every diagnostic has a stable code (`alang explain E0306`) and is also available as JSON, so an
agent never has to parse prose:

```
$ bin/alang check oops.al --json
{
  "ok": false,
  "file": "oops.al",
  "errors": 1,
  "warnings": 0,
  "diagnostics": [
    {
      "severity": "error",
      "code": "E0208",
      "title": "aliased mut argument",
      "message": "`s` is passed as `mut` and also used in another argument of the same call",
      "file": "oops.al",
      "line": 3,
      "column": 19,
      "length": 1,
      "source_line": "    append(mut s, s);",
      "hint": "copy the value into a separate variable first, then pass the copy"
    }
  ],
  "memory": null
}
```

Rust has the best diagnostics among mainstream languages, with stable codes and JSON output, and
ALang follows the same model. The difference is in scope: ALang's language is small enough that
every error has a single concrete fix, and the hints are written for the mistakes agents actually
make.

## 7. Built for agent tool use

In most languages, giving an agent a tool takes three separate pieces: the function, a JSON Schema
for its arguments written by hand (or generated with Pydantic, Zod or schemars), and a sandbox
with time and memory limits so the code can run safely. In ALang the signature is the schema, and
the language is the sandbox:

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

test "shares add up to the total" {
    var sum: i64 = 0;
    for s in split_bill(1001, 4) {
        sum += s;
    }
    assert sum == 1001;
}
```

```
$ bin/alang tools bill.al            # name, description (from ///) and input_schema
$ bin/alang call bill.al split_bill '{"total_cents": 1000, "people": 3}'
{"ok": true, "result": [334, 333, 333], "stdout": ""}
$ bin/alang call bill.al split_bill '{"total_cents": "1000", "people": 3}'
{"ok": false, "error": {"code": "bad_arguments", "message": "total_cents: expected an integer"}}
$ bin/alang call bill.al split_bill '{"total_cents": 1000, "people": 0}'
{"ok": false, "error": {"code": "R0006", "title": "precondition failed", "message": "precondition of `split_bill` failed: requires people > 0 and people <= 16", ...}}
```

Arguments are validated against the parameter types before the call. A broken precondition is
reported against the caller, and a broken `ensures` against the function, so the agent knows which
side to fix. A trap comes back as JSON instead of crashing the host.

**Why not sandbox Python instead?** A container, a WebAssembly runtime or a seccomp profile limits
the damage. It does not make the program terminate, so you get a timeout instead of an answer. It
does not make the program deterministic, so a flaky result stays flaky. And it does not tell the
agent what went wrong in terms it can act on. ALang gives its guarantees before the code runs,
without a sandbox, and its failures carry a code, a location and a fix.

## 8. Its closest relatives

ALang is not the first language to give up power for guarantees. It borrows from several:

- **Starlark** (Bazel's configuration language) is deterministic and hermetic, and Bazel's dialect
  forbids recursion and `while` loops. But it is dynamically typed, its values live on a
  garbage-collected heap, and type errors only show up at run time.
- **eBPF** proves at load time that kernel programs terminate and access memory safely. But it is
  a bytecode for kernel hooks, not a language to write tools in.
- **NASA/JPL's "Power of 10" rules** for safety-critical C (Holzmann, 2006) ask programmers to
  avoid recursion, bound every loop and avoid dynamic allocation. In C these are guidelines,
  enforced by external checkers on top of a language that still has undefined behavior. In ALang
  most of them are part of the language:

| Power of 10 rule | In ALang |
|------------------|----------|
| 1. Simple control flow, no recursion | no `goto`; recursion is error E0401 |
| 2. Every loop has a fixed upper bound | `while ... limit n` is required; `for` loops are bounded by construction |
| 3. No dynamic allocation after initialization | there is no heap at all |
| 5. Use assertions | `assert`, `requires` and `ensures` are built in (their number is up to you) |
| 6. Smallest possible scope for data | there are no global variables |
| 7. Check the return value of non-void functions | an ignored result is warning W0302 |
| 8. Limited use of the preprocessor | there is no preprocessor |
| 9. Restricted pointer use, no function pointers | there are no pointers and no function values |

## 9. What you give up

These guarantees have costs, and they are deliberate:

- **Fixed capacities.** You choose `str[64]` or `list[Item; 16]` up front, and going past the
  capacity traps (R0004).
- **No recursion.** Tree and graph algorithms use an explicit work list (see
  [AGENTS.md](../AGENTS.md#patterns)).
- **No I/O beyond `print`.** The host reads files and calls the network, then passes the data to a
  `tool fn`.
- **A small language.** Version 0.1 has no modules, generics, slices or standard library beyond
  the builtins, and no `sin`, `exp` or `pow`.
- **No ecosystem.** There are no packages to install, and no packages to vet.

Use Python, Go or Rust to build applications, talk to the outside world and reuse libraries. Use
ALang for the self-contained computations an agent writes and calls on its own: parsing, scoring,
validating, planning, pricing and other business rules. These have to be correct, repeatable and
safe to run the first time.

## 10. Measuring it

The sections above show what ALang guarantees. They do not show that models write better tools in
it: that is an empirical question, and models have seen far more Python than ALang. The benchmark in
[bench/](../bench) measures it: a model writes the same 20 tools in both languages, gets the same
feedback, and the results are graded on hidden cases, counting how often a tool that passed its
examples still returns wrong values without any error.
