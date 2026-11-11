# ALang Language Specification (v0.1)

ALang is a small, statically typed language designed to be **written by AI agents** and **run safely**.
Four properties drive every rule:

1. **No heap.** Every value has a compile-time size and lives on the stack or in static data. There is
   no `new`, pointer, `null`, garbage collector or allocator.
2. **Deterministic.** Output is a pure function of the source and the tool arguments: the same bytes and
   the same trap on every machine, compiler and locale.
3. **Always terminates.** No recursion, and every `while` loop states an iteration limit.
4. **One obvious way.** Few constructs, explicit types, no implicit conversions, no shadowing, no
   precedence puzzles; every mistake gets a coded diagnostic with a concrete fix.

The grammar, [`grammar/ALang.g4`](../grammar/ALang.g4) (ANTLR 4), is the single source of truth for
syntax; this document describes semantics. Diagnostic codes are listed in [ERRORS.md](ERRORS.md).

## 1. Lexical structure

- Source files are UTF-8, extension `.al`. Statements end with `;`; blocks always use `{ }`.
- `// ...` comments to the end of the line. `/// ...` is a **doc comment**: it documents the next
  declaration, struct field, enum variant or parameter, and tool definitions show it (§10). There are no
  block comments.
- Identifiers: `[a-zA-Z_][a-zA-Z0-9_]*`. `_` alone is the wildcard, not a name.
- Reserved words: `fn let var const struct enum if else while for in match break continue return assert
  requires ensures mut and or not true false bool i8 i16 i32 i64 u8 u16 u32 u64 f32 f64 str list`.
  `limit`, `test`, `tool` and `result` are contextual and may be used as names.
- Builtin function names (`print len cap push pop clear append abs min max sqrt floor ceil panic`)
  cannot be redefined, but may be variable names.

| Literal | Examples | Notes |
|---------|----------|-------|
| integer | `42`, `-7`, `1_000_000`, `0xFF`, `0b1010` | typed by context, default `i64` |
| float | `1.0`, `0.5`, `2.5e-3`, `1e9` | digits required on both sides of `.`; `f64` unless the context is `f32` |
| string | `"hello\n"` | escapes `\n \t \r \0 \\ \" \' \xHH`; type `str[N]`, N its byte length |
| character | `'a'`, `'\n'`, `'\x7f'` | one byte; an **integer** constant (usually used as `u8`) |
| bool | `true`, `false` | |

An integer literal takes the integer or float type the context expects and is range-checked at compile
time (`let b: u8 = 300;` is E0202); with no expected type it is `i64` (or `u64` if it only fits there).
In a binary operation a literal takes the other operand's type (`x + 1` with `x: u8` adds two `u8`); when
both are literals, a float literal makes both floats (`2 + 1.5` is `3.5`). An array literal takes its
element type from its first element that is not a literal, and `str`/`list` elements widen to the
largest capacity (`["a", "bbb"]` is a `[str[3]; 2]`). A literal in a float context is rounded once, to
nearest-even, directly to `f32` or `f64`; one that would round to `inf` is E0202.

## 2. Types

| Type | Meaning | Size |
|------|---------|------|
| `bool` | `true` / `false` | 1 |
| `i8 i16 i32 i64` | signed two's complement integers | 1, 2, 4, 8 |
| `u8 u16 u32 u64` | unsigned integers | 1, 2, 4, 8 |
| `f32 f64` | IEEE 754 binary32 / binary64 | 4, 8 |
| `str[N]` | text of 0..N bytes (UTF-8), stored inline | 4 + N (aligned) |
| `[T; N]` | array of exactly N elements | N × size(T) |
| `list[T; N]` | list of 0..N elements, stored inline | 4 + N × size(T) (aligned) |
| `Name` | a declared `struct` or `enum` | sum of fields / tag + largest payload |

`N` is a compile-time integer constant (a literal or a `const`) from 1 to 1 048 576. There are no
pointer, reference, function, generic or dynamically sized types.

All types are **values**: assigning, passing, returning, storing in a field or element, and iterating
all copy, so two variables never share storage (except through a `mut` parameter, §5).

**Assignability.** A value of type `A` can be stored where `B` is expected only if `A` and `B` are the
same type, or both are `str[M]`/`str[N]`, or both are `list[T; M]`/`list[T; N]`, with `M <= N`
(widening). Everything else needs an explicit conversion: arrays never convert to lists, but an array
**literal** `[a, b, c]` (or `[v; n]`) can initialize a list with enough capacity, and `[]` is the empty
list.

**Structs** are built with named arguments, every field exactly once, in any order:
`Point(x: 1.0, y: 2.0)`; fields are read and written with `p.x`. A struct cannot contain itself,
directly or indirectly (E0403): store linked items in a list and refer to them by index.

```
struct Point {
    x: f64,
    y: f64,
}

enum Shape {
    Circle(f64),
    Rect(f64, f64),
    Empty,
}
```

**Enum** variants are always qualified (`Shape.Empty`, `Shape.Circle(2.0)`), and payloads are read only
with `match`. There is no `null`: model absence with an enum such as `enum MaybeI32 { None, Some(i32) }`.

**Equality and ordering.** `==` and `!=` work on every type (strings, and lists of the same element type,
of different capacities may be compared; aggregates compare element-wise). `< <= > >=` work on numbers of
the same type only. Floats follow IEEE 754: `NaN != NaN`, `-0.0 == 0.0`.

## 3. Declarations

A program is a sequence of declarations, in any order (everything can refer to everything):

```
const NAME: Type = constant-expression;
struct Name { field: Type, ... }
enum Name { Variant, Variant(Type, ...), ... }
fn name(param: Type, param: mut Type) -> ReturnType requires cond ensures cond { ... }
tool fn name(param: Type) -> ReturnType { ... }
test "description" { ... }
```

- **Constants** are evaluated at compile time from literals, other constants, operators, conversions,
  `len`/`cap`/`abs`/`min`/`max`/`sqrt`/`floor`/`ceil` and struct, enum and array literals; user function
  calls are not allowed (E0209). There are no global variables.
- **`fn main()`** takes no parameters and returns nothing; `alang run` calls it.
- **`tool fn`** is callable with JSON arguments (§10) and cannot take `mut` parameters.
- **`test "..."`** blocks are run by `alang test`, each from a fresh state.

Names are unique: two items cannot share a name, and **locals cannot shadow** (E0105): a local may not
reuse the name of a local still in scope, a parameter, a constant or a type. Sibling blocks may reuse
names.

## 4. Statements

| Statement | Form |
|-----------|------|
| binding | `let name: Type = expr;` (immutable), `var name: Type = expr;` (mutable) |
| assignment | `place = expr;` and `+= -= *= /= %=` |
| condition | `if c { } else if c { } else { }` |
| loops | `while c limit n { }`, `for i in a..b { }` (half-open), `for x in xs { }` (array, list or str: `u8` bytes) |
| match | `match e { Pattern => { } ... }` |
| jumps | `break;` `continue;` `return;` `return expr;` |
| assertion | `assert cond;` `assert cond, "message";` |
| call | `f(args);` |

- Every binding has an explicit type and an initializer.
- A `place` is a variable followed by field accesses and indexes (`inv.items[i].quantity`); its root must
  be a `var` or a `mut` parameter (E0205).
- Conditions must be `bool`; there is no truthiness (E0211).
- `while c limit n`: `n` (any integer type) is evaluated once, before the first test of `c`. The program
  traps (R0008) when the body is about to run for the `n + 1`-th time. The limit is a promise about the
  algorithm, e.g. `limit 64` for a binary search over a 64-bit range.
- `for i in a..b`: `a` and `b` are evaluated once and are integers of the same type, which `i` has
  (`i64` for literals). `for x in xs` iterates a snapshot of `xs` taken once: changing `xs` in the loop
  does not affect it.
- Loop variables, match bindings and `let` bindings are immutable.
- Only calls may be expression statements (E0305); ignoring a non-void result is warning W0302.
- A function with a return type must return on every path; loops never count as returning (E0301).

**Match** patterns: `Enum.Variant`, `Enum.Variant(a, b)` (bindings; `_` ignores one), integer and
character literals (`3`, `-1`, `'x'`), string literals, `true`, `false`, and `_` (anything; must be
last). Each arm body is a block. Match works on enums, integers, `bool` and `str`, and must be
exhaustive (E0306): all enum variants or both bools, or a final `_`, which integers and strings always
need. Duplicate or unreachable arms are E0307.

## 5. Functions and `mut` parameters

```
fn scale(p: Point, k: f64) -> Point {            // p is a read-only copy
    return Point(x: p.x * k, y: p.y * k);
}

fn shift(p: mut Point, dx: f64) {               // p refers to the caller's variable
    p.x += dx;
}
```

- Plain parameters are **read-only values**. `mut` parameters are passed **by reference**, and the call
  site says so: `shift(mut origin, 1.0)` (E0207), with a mutable place of exactly the parameter type.
- `mut` references cannot be stored, returned or captured, so they never dangle.
- **Exclusive access (E0208):** a variable passed as `mut` may not appear in another argument of the
  same call: `append(mut s, s)` is rejected; copy first.
- **Mutating calls are statements (E0215):** a call with a `mut` argument is a statement of its own or the
  whole right-hand side of `let`/`var`/assignment/`return`, so every mutation is at a visible point.
- **No recursion (E0401):** the call graph, contracts included, must be acyclic. Use a loop and a
  fixed-capacity work list.

**Contracts.** `requires` clauses are checked on entry, in order; a failure is trap R0006, reported **at
the call site** (the caller broke the contract). `ensures` clauses are checked on every return, with
`result` as the return value and `mut` parameters at their final values; a failure is trap R0007,
reported at the clause, with the returned value.

```
fn isqrt(x: i64) -> i64
    requires x >= 0
    ensures result * result <= x
{
    ...
}
```

## 6. Expressions

ALang keeps ordinary arithmetic precedence and refuses to guess anywhere else:

| Level | Operators | Rule |
|-------|-----------|------|
| unary | `-x` `~x` | bind tightest |
| multiplicative | `* / %` | left-associative |
| additive | `+ -` | left-associative |
| comparison | `== != < <= > >=` | operands are additive expressions; **comparisons never chain** |
| negation | `not` | `not a == b` means `not (a == b)` |
| logical | `and`, `or` | chains of one operator; **mixing `and` with `or` needs parentheses** |
| bitwise | `& | ^ << >>` | operands are unary expressions; **never combined with another operator without parentheses** |

So `a + b * c`, `x < y and y < z`, `not done`, `(flags & MASK) == 0` and `(a and b) or c` are valid,
while `a & b == 0`, `a < b < c`, `a and b or c` and `x << 2 + 1` are syntax errors with a fix hint.

- `+ - * /` take two numbers of the **same type**, `%` two integers; mixing types is an error.
- Integer arithmetic is **checked**: overflow traps (R0001), division or remainder by zero traps (R0002).
  Division truncates toward zero; `%` has the dividend's sign; `MIN / -1` overflows and `MIN % -1` is `0`.
- Shifts: the left operand gives the type; the amount may be any integer type and must be in
  `0..bits-1` (R0012). Bits shifted out are discarded. `>>` is arithmetic for signed types, logical for
  unsigned ones. `& | ^ ~` take integers of the same type.
- `and or not` take `bool`; `and`/`or` short-circuit.
- Float arithmetic is IEEE 754, round-to-nearest-even, without fused multiply-add. Division by zero gives
  `inf`/`nan`, never a trap.

**Conversions** are calls to a primitive type: `i64(x)`, `u8(n)`, `f64(count)`.

| From → To | Behavior |
|-----------|----------|
| integer → integer | value preserved; out of range traps (R0009) |
| integer → float | rounded to nearest (ties to even) |
| float → integer | truncated toward zero; NaN or out of range traps (R0009) |
| f64 → f32 | rounded to nearest; may become `inf` |
| f32 → f64 | exact |
| bool → integer | `true` → 1, `false` → 0 |
| anything → bool | not allowed (E0214); compare instead |

**Evaluation order** is fully specified, so side effects (`print`, traps) always happen in the same
order:

1. Operands, arguments, array elements and struct-literal fields are evaluated **left to right**, then
   the operation is performed; `and`/`or` evaluate the right operand only when needed.
2. An assignment evaluates the **right-hand side first**, then the target place (its indexes and bounds
   checks), then stores.
3. A `while` limit is evaluated once before the condition; `for` bounds and collections once before the
   first iteration.
4. A call evaluates all arguments, checks `requires`, runs the body, then checks `ensures`.

## 7. Memory model

- The only memory a program uses is **static data** (constants) and **stack frames**; there is no heap.
- Without recursion the call graph is a DAG, so the worst-case stack is computable:
  `stack(f) = frame(f) + max(stack(g) for g called by f)`. A frame counts the parameters (aggregates and
  `mut` parameters by address), the locals, the return value and the aggregate temporaries of all its
  statements (literals, call results, copies, the snapshot of `for x in xs`), so the figure is an upper
  bound: temporaries of different statements are added up, because the C backend gives each its own
  variable and a C compiler need not reuse a dead one's stack slot. `alang check` prints the bound, and
  `--memory` prints it per function.
- Every command checks the program against a **memory budget**: 8 MiB (8 388 608 bytes) unless
  `--max-stack BYTES` sets another (`0`: none). An entry point (`main`, a tool or a test) whose worst-case
  stack exceeds it is E0402. Constants are evaluated while checking, so they are checked first: together,
  with the values their initializers build, they must fit the same budget (E0405). A program that passes
  cannot exhaust the interpreter's memory and fits the default C stack of Linux and macOS.
- Large values can be passed as `mut` to avoid copies; the C backend passes read-only aggregate
  parameters by address, so they are not copied either.
- Capacities are part of the type: `push` past capacity traps (R0004); nothing reallocates.
- Nesting is limited to 128 levels (E0404), counted on the source before anything else runs: each
  bracket and each chained operator (`.field` and `[index]` included) counts one level, and a function,
  constant or type adds the levels
  of every function, constant and type it uses. The toolchain recurses over the program, so a fixed
  limit, not the machine's stack size, decides whether a program can be checked, run or compiled.

## 8. Determinism

**The same program with the same tool arguments produces the same output bytes and the same trap, every
time, everywhere:**

- **No sources of nondeterminism:** no clock, random numbers, environment, file or network I/O, threads,
  hash-ordered collections, addresses or uninitialized memory. The only effect is `print`; the only
  inputs are a tool call's arguments.
- **No undefined or implementation-defined behavior:** integer widths are fixed, and overflow, bad
  indexes, shifts and conversions, and failed contracts trap with a fixed code, message and location.
  Evaluation order is fixed (§6).
- **Portable floats:** only correctly rounded IEEE 754 operations exist (`+ - * /`, `sqrt`, `floor`,
  `ceil`, `abs`, `min`, `max`, conversions); `sin`, `exp`, `pow` and the like differ between math
  libraries and are left out. `min`/`max` are `if b < a then b else a` / `if b > a then b else a`, which
  fixes NaN handling. The C backend refuses `-ffast-math` and x87 excess precision, and disables fused
  multiply-add contraction.
- **Exact text formatting** (§9), independent of locale and C library.
- **A deterministic step budget:** the interpreter counts executed statements, and `--max-steps`
  (default 100 000 000) stops a runaway program with R0011 after exactly the same number of steps.
- **Deterministic tooling:** diagnostics are sorted by position, JSON has a fixed key order, generated C
  is byte-identical for identical input, and nothing depends on the host locale or line endings.

The test suite runs every program repeatedly and under several locales, and compares the C backend byte
for byte with the interpreter under several compilers and optimization levels.

## 9. Builtins and text formatting

| Builtin | Description |
|---------|-------------|
| `print(values...)` | Writes the text of each value (no separator) and a newline. |
| `len(x) -> i64` | Elements of an array/list, bytes of a str. |
| `cap(x) -> i64` | Capacity N of a `list[T; N]` or `str[N]`. |
| `push(mut xs, v)` | Appends an element (a `u8` byte for str). Traps if full (R0004). |
| `pop(mut xs) -> T` | Removes and returns the last element. Traps if empty (R0012). |
| `clear(mut xs)` | Removes all elements. |
| `append(mut s, values...)` | Appends the text of each value to a str. Traps if it does not fit (R0004). |
| `abs(x)`, `min(a, b)`, `max(a, b)` | Numeric helpers (same-type operands). `abs` of the minimum integer traps. |
| `sqrt(x)`, `floor(x)`, `ceil(x)` | On `f32`/`f64`, correctly rounded. |
| `panic(message)` | Stops with trap R0010. |

The text of a value (for `print`, `append`, trap messages and the left/right values of a failed
`assert`):

- integers in decimal; `bool` as `true`/`false`;
- floats rounded to 6 decimal places (round-half-even on the exact binary value), trailing zeros removed
  but one decimal kept: `1.5`, `2.0`, `0.333333`, `100000000000000000000.0` for `1e20`, `-0.0`, `nan`,
  `inf`, `-inf`;
- a top-level `str` as its raw bytes; a `str` inside an aggregate quoted with escapes: `"a\n"`;
- arrays and lists as `[1, 2, 3]`, structs as `Point(x: 1.0, y: 2.0)`, enums as `Shape.Empty` or
  `Shape.Circle(2.0)`;
- a `u8` (a character literal too) as a number: add a character to a string with `push(mut s, 'a')`;
  `append(mut s, 'a')` appends `97` (warning W0303).

## 10. Tools: calling ALang from an agent

A `tool fn` can be called with JSON. `alang tools file.al` prints Anthropic-style tool definitions
(`name`, `description` from the `///` comment, `input_schema`). The doc comments of its parameters, and
of the structs, enums, fields and variants they use, become the `description`s in `input_schema`; a
documented variant without a payload is listed as a `{"const": "Variant"}` alternative of its own, so it
can carry one.

The description also lists every `requires` clause, the return type and every `ensures` clause, each on a
line of its own. A `requires` clause that compares a parameter, a field of one, or the `len` of either with
a constant expression also narrows the schema, so the model sees the limit before it calls:
`requires people > 0 and people <= 16` gives `"minimum": 1, "maximum": 16`. A bound on the `len` of a
list sets `minItems`/`maxItems`; on a `str`, whose `len` counts bytes while JSON Schema counts
characters, an upper bound sets `maxLength` and a lower one only `"minLength": 1`. Other clauses are only
described. The schema never rejects a call the contract accepts, and the call still checks every clause
(R0006).

`alang call file.al name '{"arg": ...}'`
runs one call and prints `{"ok": true, "result": ..., "stdout": "..."}` or
`{"ok": false, "error": {...}}`. With `-` in place of the JSON the arguments are read from stdin (as
UTF-8; other bytes are an error); with nothing they are `{}`. `stdout` keeps the first 1 MiB of output;
past that, `"stdout_truncated": true` and `"stdout_bytes"` (the full length) are added.

| ALang type | JSON |
|------------|------|
| `bool` | boolean |
| integers | integer, range-checked (`minimum`/`maximum` in the schema) |
| floats | number; JSON has no NaN or infinity, so a result holding one fails with `bad_result`, and an argument too large for the type is rejected |
| `str[N]` | string of at most N UTF-8 bytes; a result that is not UTF-8 text fails with `bad_result` |
| `[T; N]` | array of exactly N items |
| `list[T; N]` | array of at most N items |
| struct | object with every field required, no extra fields |
| enum | `"Variant"` without payload, `{"Variant": [v1, v2]}` with |

Arguments must be strict JSON (RFC 8259: ASCII digits, no leading zeros, escaped control characters,
paired surrogates) and are validated against the parameter types before the call; unknown, missing or
duplicate keys are errors. With no I/O, no heap and bounded loops, a tool written by an agent is safe to
run: it cannot touch the host, cannot exhaust memory, and terminates.

## 11. Runtime errors (traps)

A trap stops the program (or the current test or tool call) and reports a code `R0001`–`R0012`, a
message and the source location; the interpreter also prints the call stack. The exit code is 1. There
are no exceptions: model recoverable failures as enum return values (see `examples/rpn_calculator.al`).
[ERRORS.md](ERRORS.md) lists every trap.

## 12. The C backend

`alang build file.al -o file.c` produces one self-contained C99 file:

- Values become C structs (arrays are wrapped so they copy by value); `mut` parameters become pointers,
  read-only aggregates `const` pointers.
- No allocation and no stdio: the object file references only `write`, `_Exit` and exact math functions
  (`sqrt`, `floor`, `ceil`, `frexp`, `ldexp`). For bare-metal targets define `AL_WRITE(fd, ptr, len)`.
- Expressions are lowered to statements so the ALang evaluation order is kept exactly, with every check
  (overflow, bounds, contracts, loop limits) and identical trap messages.
- The runtime prelude formats numbers itself (floats exactly, with big-number arithmetic), so output is
  byte-identical to the interpreter's.

Recommended: `cc -std=c99 -O2 file.c -lm`. The test suite checks gcc and clang at `-O0` to `-O3`.
