# ALang compared with other languages

ALang is for **code an AI agent writes and someone runs without reading it first**. Four questions matter
before it runs: will it finish, how much memory can it use, will it give the same answer next time, and
when it is wrong, will it say how to fix it? Mainstream languages answer "it depends"; ALang's compiler
enforces a rule for each. The video [demo/why.mp4](demo/why.mp4) shows six cases side by side.

> `DocsTest` checks that every ALang program here compiles and passes, and that every diagnostic and trap
> shown is exact. Other languages' output: x86-64 Linux, Python 3.13, Node.js 22, Go 1.24, Rust 1.97,
> GCC 13.3, Clang 18, Java 21.

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
| Tool schema for an agent | generated from the signature, doc comments and contract | written by hand or with a library | written by hand or with a library | written by hand or with a library | written by hand or with a library | written by hand |

## 1. Arithmetic never goes wrong quietly

`(lo + hi) / 2` sat in the JDK's `Arrays.binarySearch` for nine years. In ALang:

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

C (`gcc -O2`), Java, Go `int32` and release-build Rust all print `-97483648` (in C, by undefined
behavior); debug Rust panics. JavaScript silently rounds above 2^53, and a Python integer grows until memory
runs out. ALang traps the same way in the interpreter and in compiled C, and the hint names the fix:

```alang
fn midpoint(lo: i32, hi: i32) -> i32 {
    return i32((i64(lo) + i64(hi)) / 2);
}

test "midpoint of large values" {
    assert midpoint(2_000_000_000, 2_100_000_000) == 2_050_000_000;
    assert midpoint(-2_000_000_000, 2_100_000_000) == 50_000_000;
}
```

Bad indexes (R0003), division by zero (R0002), lossy conversions like `u8(300)` (R0009) and invalid shifts
(R0012) trap too: no undefined behavior, no wraparound, no Python-style `xs[-1]` reading the last element.

## 2. The same answer on every run and every machine

**Iteration order.** Python's `set` order changes per process; Go and Rust randomize map order on
purpose. ALang lists keep their order, and maps and sets iterate their keys in sorted order.

**Floating point.** C compilers may fuse `a * b + c` into one multiply-add, so the same source prints `0`
or `5.55112e-17` depending on `-march`. ALang always rounds twice: the C backend turns contraction off and
refuses `-ffast-math`, and the tests compare it byte for byte with the interpreter under gcc and clang at
`-O0` to `-O3`, even with `-ffp-contract=fast` on an FMA target.

```alang
fn mul_add(a: f64, b: f64, c: f64) -> f64 {
    return a * b + c;
}

test "a * b + c rounds twice on every compiler and CPU" {
    assert mul_add(0.1, 10.0, -1.0) == 0.0;
}
```

Math libraries differ in the last bits of `sin`, `exp` and `pow`, so ALang computes them itself, from
correctly rounded operations only, identically in both backends.

**Locale.** Java's `String.format("%.2f", 3.14159)` prints `3,14` under a German locale. ALang formats
numbers itself, and the tests run every program under several locales.

**No hidden inputs.** No clock, randomness, environment, threads or I/O but `print`; input arrives whole.
A task that needs randomness takes a seed.

## 3. Programs that always finish

Recursion overflows the stack at a depth that depends on the platform. In ALang it is a compile error:

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

And every `while` states how many iterations it may run:

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

A limit too small traps with R0008 instead of hanging. The step budget (`--max-steps`) counts statements
and the data they touch, so a program stops after the same amount of work on every run.

## 4. Memory you can read before running

Every type has a compile-time size and there is no heap or recursion, so the checker bounds the memory of
every entry point:

```
$ bin/alang check examples/maze_bfs.al --memory
ok: examples/maze_bfs.al: 3 function(s), 1 test(s), 0 warning(s)
memory: heap 0 B, static 96 B, worst-case stack 2738 B
  routine                                     frame (B)    stack (B)
  fn find                                            77           77
  fn shortest_path                                 2617         2694
  fn main                                            44         2738  (entry)
  test "finds the shortest path"                      0         2694  (entry)
```

A program over the budget (8 MiB, or `--max-stack`) does not compile (E0402, E0405). Elsewhere, memory use
is known only after running, or with external tools.

## 5. No aliasing surprises

Assignment, parameters, returns and fields all copy. Python's `grid = [[0] * 3] * 3; grid[0][0] = 1`
changes all three rows; ALang's does not:

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
    assert copy[2][2] == 9;
    assert grid[2][2] == 0;
}
```

A function that changes its caller's data says so with `mut`, and so does the call:

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

`mut` references cannot be stored or returned, so they never dangle. A `mut` variable cannot appear twice
in a call (E0208), and a call with a `mut` argument is its own statement (E0215).

## 6. One way to write it, and errors that say which way

In C and JavaScript, `flags & MASK == 0` means `flags & (MASK == 0)`, and `0 < x < 10` is `true` for 50.
ALang keeps arithmetic precedence and refuses to guess elsewhere:

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

Habits agents carry over get the ALang spelling:

| An agent writes | ALang answers |
|-----------------|---------------|
| `if ready && !done` | E0001: use the keyword `and` instead of `&&` |
| `let x = 5;` | E0001: every variable needs an explicit type: `let x: i32 = 0;` |
| `i++;` | E0001: there is no `++` operator; write `x += 1;` |
| `Color::Red` | E0001: enum variants are accessed with a dot: `Color.Red` |
| `Point { x: 1, y: 2 }` | E0001: struct values are built with named arguments: `Point(field: value, ...)` |
| `x > 0 ? 1 : 2` | E0001: there is no `?:` operator; use if/else or match |
| `1 if x > 0 else -1` | E0001: there is no `a if c else b`; write `if c then a else b` |
| `loop { }` | E0001: there is no `loop`; use `while true limit N { ... break; }` |
| `print(f"x = {x}")` | E0001: there are no format strings; pass the parts in order, `print("x = ", x)` |
| `class Point { }`, `pub fn` | E0001: there are no classes or methods; a `struct` and functions that take it (and no `pub`: remove it) |
| `const` inside a function | E0001: a const is declared at the top level; inside a function, use `let` |
| `a and b or c` | E0001: `and` and `or` cannot be mixed without parentheses: `(a and b) or c` |
| `xs.len()` | E0102: there are no methods; pass the value as the first argument: `len(xs)` |
| `let x: i64 = null;` | E0101: ALang has no null; a value that may be absent is an `Option<T>` |
| `if count { }` | E0211: there is no truthiness; compare explicitly, e.g. `x != 0` |
| `10.0 / n` with `n: i64` | E0201: convert one side explicitly, e.g. `f64(n)` |
| `let sep: u8 = ",";` | E0201: a character is written in single quotes, `','` (with a fix) |
| a `match` that forgets a variant | E0306: match is not exhaustive: missing `Color.Blue` |
| a function that can fall off the end | E0301: add `return <i64>;` at the end of the function |
| an inner `let x` that reuses a name | E0105: ALang has no shadowing; choose a new name |

Every diagnostic has a stable code (`alang explain E0306`) and comes as JSON with `--json`.

## 7. Built for agent tool use

A tool usually takes a function, a hand-written JSON Schema and a sandbox. In ALang the signature and
contract are the schema, and the language is the sandbox. With `split_bill` from the
[README](../README.md), whose `requires people > 0 and people <= 16` becomes `"minimum": 1, "maximum": 16`:

```
$ bin/alang tools bill.al            # name, description (from ///) and input_schema
$ bin/alang call bill.al split_bill '{"total_cents": 1000, "people": 3}'
{"ok": true, "result": [334, 333, 333], "stdout": "", "steps": 16}
$ bin/alang call bill.al split_bill '{"total_cents": "1000", "people": 3}'
{"ok": false, "error": {"code": "bad_arguments", "message": "total_cents: expected an integer"}}
$ bin/alang call bill.al split_bill '{"total_cents": 1000, "people": 0}'
{"ok": false, "error": {"code": "R0006", "title": "precondition failed", "message": "precondition of `split_bill` failed: requires people > 0 and people <= 16", ...}}
```

Arguments are validated against the parameter types; a broken `requires` blames the caller, a broken
`ensures` the function; a trap comes back as JSON. `alang serve` offers the same tools over MCP.

**Why not sandbox Python?** A sandbox limits damage, but does not make the program terminate,
deterministic, or explain its failures. ALang gives its guarantees before the code runs.

## 8. Its closest relatives

- **Starlark** is deterministic and hermetic, but dynamically typed and garbage-collected.
- **eBPF** proves termination and memory safety at load time, but is bytecode for kernel hooks.
- **NASA/JPL's "Power of 10"** rules for C are enforced by external checkers; in ALang most are the
  language: no recursion (E0401), every loop bounded, no heap, built-in `assert`/`requires`/`ensures`, no
  globals, ignored results warned (W0302), no preprocessor, no pointers.

## 9. What you give up

- **Fixed capacities:** choose `str[64]` or `list[Item; 16]` up front; going past traps (R0004).
- **No recursion:** use an explicit work list ([AGENTS.md](../AGENTS.md#patterns)).
- **No I/O beyond `print`:** the host passes data to a `tool fn`, or to `fn main` on stdin.
- **A small language** and standard library ([SPEC.md](SPEC.md#9-builtins-and-the-standard-library)), generics without closures.
- **No ecosystem:** no packages to install, and none to vet.

Use Python, Go or Rust for applications and I/O. Use ALang for the self-contained computations an agent
writes and calls (parsing, scoring, validating, pricing) that must be correct, repeatable and safe the
first time. The [benchmark](../bench) measures whether models actually write better tools in it.
