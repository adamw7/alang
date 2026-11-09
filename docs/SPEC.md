# ALang Language Specification (v0.1)

ALang is a small, statically typed language designed to be **written by AI agents** and **run safely**.
Four properties drive every rule in this document:

1. **No heap.** Every value has a size known at compile time and lives on the stack or in static
   data. There is no `new`, no pointer type, no `null`, no garbage collector, no allocator.
2. **Deterministic.** A program's output is a pure function of its source text and its tool
   arguments: same input, same bytes out, same trap, on every machine, compiler and locale.
3. **Always terminates.** No recursion, and every `while` loop states an iteration limit.
4. **One obvious way.** Few constructs, explicit types, no implicit conversions, no shadowing,
   no precedence puzzles. Mistakes produce a coded diagnostic with a concrete fix.

The grammar is [`grammar/ALang.g4`](../grammar/ALang.g4) (ANTLR 4). It is the single source of
truth for syntax; this document describes semantics. Diagnostic codes are listed in
[ERRORS.md](ERRORS.md).

---

## 1. Lexical structure

- Source files are UTF-8, extension `.al`.
- Comments: `// ...` to end of line. `/// ...` is a **doc comment**; it documents the next
  declaration and becomes the description of a `tool fn`. There are no block comments.
- Identifiers: `[a-zA-Z_][a-zA-Z0-9_]*`. `_` alone is the wildcard, not a name.
- Statements end with `;`. Blocks always use `{ }`.
- Reserved words: `fn let var const struct enum if else while for in match break continue
  return assert requires ensures mut and or not true false bool i8 i16 i32 i64 u8 u16 u32 u64
  f32 f64 str list`. The words `limit`, `test`, `tool` and `result` are contextual and may be
  used as ordinary names.
- Builtin function names (`print len cap push pop clear append abs min max sqrt floor ceil panic`)
  cannot be redefined, but may be used as variable names.

### Literals

| Literal | Examples | Notes |
|---------|----------|-------|
| integer | `42`, `-7`, `1_000_000`, `0xFF`, `0b1010` | typed by context, default `i64` |
| float | `1.0`, `0.5`, `2.5e-3`, `1e9` | digits are required on both sides of `.`; `f64` unless the context is `f32` |
| string | `"hello\n"` | escapes `\n \t \r \0 \\ \" \' \xHH`; type `str[N]` where N is the byte length |
| character | `'a'`, `'\n'`, `'\x7f'` | one byte; an **integer** constant (usually used as `u8`) |
| bool | `true`, `false` | |

An integer literal adopts the integer or float type that the context expects and is checked
against that type's range at compile time (`let b: u8 = 300;` is error E0202). With no expected
type it is `i64` (or `u64` if it only fits there). In a binary operation a literal takes the type
of the other operand: `x + 1` where `x: u8` adds two `u8`; when both operands are literals, a float
literal makes both floats (`2 + 1.5` is `3.5`). An array literal takes its element type from its
first element that is not a literal, and `str`/`list` elements widen to the largest capacity
(`["a", "bbb"]` is a `[str[3]; 2]`). A literal in a float context is rounded once, to nearest-even,
directly to `f32` or `f64`; one that would round to `inf` is error E0202.

---

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

`N` is a compile-time integer constant between 1 and 1 048 576 (a literal or a `const`).
There are no pointer, reference, function, generic or dynamically sized types.

All types are **values**: assignment, passing a parameter, returning, storing in a field or
element, and iterating all copy. Two variables never share storage. (The only exception is a
`mut` parameter, see §5.)

### Assignability

A value of type `A` can be stored where type `B` is expected only if:

- `A` and `B` are the same type, or
- both are strings and `A = str[M]`, `B = str[N]` with `M <= N` (widening), or
- both are lists of the same element type and `A = list[T; M]`, `B = list[T; N]` with `M <= N`.

Everything else needs an explicit conversion. In particular arrays never convert to lists, but an
array **literal** `[a, b, c]` (or `[v; n]`) can initialize a list whose capacity is large enough,
and `[]` is the empty list.

### Structs

```
struct Point {
    x: f64,
    y: f64,
}
```

Struct values are built with named arguments, every field exactly once, in any order:
`Point(x: 1.0, y: 2.0)`. Fields are read and written with `p.x`. A struct cannot contain itself,
directly or indirectly (E0403); to model links, store items in a list and refer to them by index.

### Enums

```
enum Shape {
    Circle(f64),
    Rect(f64, f64),
    Empty,
}
```

Variants are always qualified: `Shape.Empty`, `Shape.Circle(2.0)`. Payload values are read with
`match` only. There is no `null`; model absence with an enum such as `enum MaybeI32 { None, Some(i32) }`.

### Equality and ordering

`==` and `!=` work on every type (strings and lists of different capacities may be compared;
aggregates compare element-wise). `< <= > >=` work on numbers of the same type only. Floats
follow IEEE 754: `NaN != NaN`, `-0.0 == 0.0`.

---

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

- **Constants** are evaluated at compile time. Their initializer may use literals, other
  constants, operators, conversions, `len`/`cap`/`abs`/`min`/`max`/`sqrt`/`floor`/`ceil`, and
  struct/enum/array literals, but no user function calls (E0209). There are no global variables.
- **`fn main()`** takes no parameters and returns nothing; `alang run` calls it.
- **`tool fn`** marks a function as callable with JSON arguments (§10). Tools cannot take `mut`
  parameters.
- **`test "..."`** blocks are run by `alang test`. Each test starts from a fresh state; since there
  are no globals, tests cannot influence each other.

Names must be unique: two items cannot share a name, and **locals cannot shadow** anything — a
local may not reuse the name of another local still in scope, a parameter, a constant or a type
(E0105). Sibling blocks may reuse names.

---

## 4. Statements

| Statement | Form |
|-----------|------|
| immutable binding | `let name: Type = expr;` |
| mutable binding | `var name: Type = expr;` |
| assignment | `place = expr;` and `+= -= *= /= %=` |
| condition | `if c { } else if c { } else { }` |
| bounded loop | `while c limit n { }` |
| range loop | `for i in a..b { }` (half-open: `a, a+1, ..., b-1`) |
| collection loop | `for x in xs { }` (array, list or str; str yields `u8` bytes) |
| match | `match e { Pattern => { } ... }` |
| jumps | `break;` `continue;` `return;` `return expr;` |
| assertion | `assert cond;` `assert cond, "message";` |
| call | `f(args);` |

Notes:

- Every binding has an explicit type and an initializer. There are no uninitialized variables.
- A `place` is a variable followed by field accesses and indexes: `inv.items[i].quantity`.
  Its root must be a `var` or a `mut` parameter (E0205).
- Conditions must be `bool`; there is no truthiness (E0211).
- `while c limit n`: `n` (any integer type) is evaluated once, before the first test of `c`. If the
  body is about to run for the `n + 1`-th time, the program traps (R0008). The limit is a
  promise about the algorithm, e.g. `limit 64` for a binary search over a 64-bit range.
- `for i in a..b`: `a` and `b` are evaluated once, are integers of the same type, and `i` has that
  type. Integer literals in ranges default to `i64`.
- `for x in xs`: the collection is evaluated **once** and iterated as a snapshot; changing `xs`
  inside the loop does not affect the iteration.
- Loop variables, match bindings and `let` bindings are immutable.
- Only calls may be used as expression statements (E0305). Ignoring a non-void result is warning W0302.
- A function with a return type must return on every path; loops never count as returning (E0301).

### Match

Patterns: `Enum.Variant`, `Enum.Variant(a, b)` (bindings, `_` to ignore), integer and character
literals (`3`, `-1`, `'x'`), string literals, `true`, `false`, and `_` (anything; must be last).
Each arm body is a block. A match must be exhaustive: all enum variants (or both bools), or a final
`_` arm; matches on integers and strings always need `_` (E0306). Duplicate or unreachable arms are
errors (E0307). Match works on enums, integers, `bool` and `str`.

---

## 5. Functions, parameters and references

```
fn scale(p: Point, k: f64) -> Point {            // p is a read-only copy
    return Point(x: p.x * k, y: p.y * k);
}

fn shift(p: mut Point, dx: f64) {               // p refers to the caller's variable
    p.x += dx;
}
```

- Plain parameters are **read-only values**.
- `mut` parameters are passed **by reference**: the callee reads and writes the caller's storage.
  The call site must say so: `shift(mut origin, 1.0)` (E0207). The argument must be a mutable place
  of exactly the parameter type (no widening).
- `mut` references are second-class: they cannot be stored, returned or captured, so they can
  never dangle.
- **Exclusive access (E0208):** a variable passed as `mut` may not appear in any other argument
  of the same call. `append(mut s, s)` is rejected; copy first.
- **Mutating calls are statements (E0215):** a call with any `mut` argument must be a statement
  of its own, or the entire right-hand side of `let`/`var`/assignment/`return`. This keeps every
  mutation at a visible, sequential point.
- **No recursion (E0401):** the call graph (including calls inside contracts) must be acyclic.
  Use a loop and an explicit fixed-capacity work list instead.

### Contracts

```
fn isqrt(x: i64) -> i64
    requires x >= 0
    ensures result * result <= x
{
    ...
}
```

`requires` clauses are checked on entry, in order; a failure is trap R0006 reported **at the call
site** (the caller broke the contract). `ensures` clauses are checked on every return; inside them
`result` is the return value and `mut` parameters show their final values. A failure is trap
R0007 reported at the clause, with the returned value.

---

## 6. Expressions

### Precedence

ALang keeps ordinary arithmetic precedence and refuses to guess anywhere else:

| Level | Operators | Rule |
|-------|-----------|------|
| unary | `-x` `~x` | bind tightest |
| multiplicative | `* / %` | left-associative |
| additive | `+ -` | left-associative |
| comparison | `== != < <= > >=` | operands are additive expressions; **comparisons never chain** |
| negation | `not` | `not a == b` means `not (a == b)` |
| logical | `and`, `or` | chains of one operator; **mixing `and` with `or` needs parentheses** |
| bitwise | `& | ^ << >>` | operands are unary expressions; **never combined with any other operator without parentheses** |

So `a + b * c`, `x < y and y < z`, `not done`, `(flags & MASK) == 0`, `(a and b) or c` are valid,
while `a & b == 0`, `a < b < c`, `a and b or c`, `x << 2 + 1` are syntax errors with a fix hint.

### Operators

- `+ - * /` on two numbers of the **same type**; `%` on integers. Mixing types is an error; convert
  explicitly.
- Integer arithmetic is **checked**: overflow traps (R0001), division or remainder by zero traps
  (R0002). Division truncates toward zero; `%` has the sign of the dividend; `MIN / -1` overflows
  and `MIN % -1` is `0`.
- Shifts: the left operand determines the type; the amount may be any integer type and must be in
  `0..bits-1` (R0012). Bits shifted out are discarded (no overflow check). `>>` is arithmetic for
  signed types and logical for unsigned types.
- `& | ^ ~` on integers of the same type.
- `and or not` on `bool`; `and`/`or` short-circuit.
- Float arithmetic is IEEE 754 with round-to-nearest-even and no fused multiply-add. Division by
  zero gives `inf`/`nan`, never a trap.

### Conversions

Written as a call to a primitive type: `i64(x)`, `u8(n)`, `f64(count)`, `f32(x)`.

| From → To | Behavior |
|-----------|----------|
| integer → integer | value preserved; out of range traps (R0009) |
| integer → float | rounded to nearest (ties to even) |
| float → integer | truncated toward zero; NaN or out of range traps (R0009) |
| f64 → f32 | rounded to nearest; may become `inf` |
| f32 → f64 | exact |
| bool → integer | `true` → 1, `false` → 0 |
| anything → bool | not allowed (E0214); compare instead |

### Evaluation order

Evaluation order is fully specified, so side effects (`print`, traps) always happen in the same
order:

1. Operands, arguments, array elements and struct-literal fields are evaluated **left to right, in
   source order**, then the operation is performed.
2. `and` / `or` evaluate the right operand only when needed.
3. In an assignment, the **right-hand side is evaluated first**, then the target place (including
   its index expressions and bounds checks), then the store.
4. In `while`, the limit is evaluated once before the condition; in `for`, range bounds and the
   collection are evaluated once before the first iteration.
5. A call evaluates all arguments, then checks `requires`, runs the body, then checks `ensures`.

---

## 7. Memory model

- Every type has a compile-time size (§2). The only memory a program uses is **static data**
  (constants) and **stack frames**. There is no heap.
- Because recursion is forbidden, the call graph is a DAG, so the worst-case stack depth is
  computable: `stack(f) = frame(f) + max(stack(g) for g called by f)`. `alang check` prints it,
  `alang check --memory` prints it per function, and `--max-stack BYTES` turns an overrun into
  error E0402.
- Large values can be passed by `mut` reference to avoid copies. Read-only aggregate parameters
  are passed by address by the C backend, so they are not copied either.
- Capacities are part of the type. `push` past capacity traps (R0004); there is no reallocation.
- Nesting is limited to 128 levels (E0404), counted on the source before anything else runs: each
  bracket and each operator chained in an expression counts one level, and a function, constant or
  type adds the levels of every function, constant and type it uses. The toolchain recurses over the
  program, so a fixed limit keeps whether a program can be checked, run or compiled the same on every
  machine, instead of leaving it to the machine's stack size.

---

## 8. Determinism

ALang guarantees that **the same program with the same tool arguments produces the same output
bytes and the same trap, every time, everywhere**. Concretely:

- **No sources of nondeterminism exist in the language:** no clock, no random numbers, no
  environment variables, no file or network I/O, no threads, no hash-ordered collections, no
  addresses, no uninitialized memory. The only effect is `print` (to standard output) and the
  only inputs are the arguments of a tool call.
- **No undefined or implementation-defined behavior.** Integer widths are fixed; overflow, bad
  indexes, bad shifts, bad conversions and failed contracts all trap with a fixed code, message
  and source location.
- **Fixed evaluation order** (§6).
- **Portable floats.** Only correctly rounded IEEE 754 operations are provided: `+ - * /`,
  `sqrt`, `floor`, `ceil`, `abs`, `min`, `max`, conversions. Transcendental functions (`sin`,
  `exp`, `pow`, ...) are deliberately absent because their results differ between math
  libraries. `min`/`max` are defined as `if b < a then b else a` / `if b > a then b else a`, so
  NaN handling is fixed. The C backend refuses to compile with `-ffast-math` or with excess
  precision (x87), and disables fused multiply-add contraction.
- **Exact text formatting** (§9), independent of locale and C library.
- **Deterministic step budget.** The interpreter counts executed statements; `--max-steps`
  (default 100 000 000) stops runaway programs with trap R0011 after exactly the same number of
  steps on every run. It counts work, not time.
- **Deterministic tooling.** Diagnostics are sorted by position; JSON output has a fixed key order;
  the generated C is byte-identical for identical input. The toolchain's own output does not
  depend on the host locale or OS line endings.

The test suite enforces this: every program is run repeatedly and under several locales, and the
C backend is compared byte-for-byte with the interpreter under multiple compilers and
optimization levels.

---

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

The text of a value (used by `print`, `append`, trap messages and the `left/right` values of a
failed `assert`) is:

- integers in decimal; `bool` as `true`/`false`;
- floats rounded to 6 decimal places (round-half-even on the exact binary value), trailing
  zeros removed but one decimal kept: `1.5`, `2.0`, `0.333333`, `1e20` prints as
  `100000000000000000000.0`, `-0.0`, `nan`, `inf`, `-inf`;
- a top-level `str` as its raw bytes; a `str` inside an aggregate quoted with escapes: `"a\n"`;
- arrays and lists as `[1, 2, 3]`; structs as `Point(x: 1.0, y: 2.0)`; enums as `Shape.Empty` or
  `Shape.Circle(2.0)`;
- a `u8` (including a character literal) prints as a number. To add a character to a string, use
  `push(mut s, 'a')`; `append(mut s, 'a')` appends `97` (warning W0303).

---

## 10. Tools: calling ALang from an agent

A `tool fn` is a function an agent can call with JSON. `alang tools file.al` prints
Anthropic-style tool definitions (`name`, `description`, `input_schema`); the description is the
`///` doc comment. `alang call file.al name '{"arg": ...}'` runs one call and prints
`{"ok": true, "result": ..., "stdout": "..."}` or `{"ok": false, "error": {...}}`.

| ALang type | JSON |
|------------|------|
| `bool` | boolean |
| integers | integer, range-checked (`minimum`/`maximum` in the schema) |
| floats | number |
| `str[N]` | string of at most N UTF-8 bytes |
| `[T; N]` | array of exactly N items |
| `list[T; N]` | array of at most N items |
| struct | object with every field required, no extra fields |
| enum | `"Variant"` for variants without payload, `{"Variant": [v1, v2]}` otherwise |

Arguments are validated against the parameter types before the call; unknown, missing or
duplicate keys are errors. Because tools have no I/O, no heap and bounded loops, running a tool
written by an agent is safe: it cannot touch the host, cannot exhaust memory, and terminates.

---

## 11. Runtime errors (traps)

A trap stops the program (or the current test or tool call) and reports a code `R0001`–`R0012`,
a message and the source location (the interpreter also prints the call stack). The process exit
code is 1. ALang has no exceptions; recoverable failures
are modeled as enum return values (see `examples/rpn_calculator.al`). The full list is in
[ERRORS.md](ERRORS.md).

---

## 12. The C backend

`alang build file.al -o file.c` produces one self-contained C99 file:

- Values become C structs (arrays are wrapped so they copy by value); `mut` parameters become
  pointers; read-only aggregates are passed as `const` pointers.
- No allocation and no stdio: the object file references only `write`, `_Exit` and exact math
  functions (`sqrt`, `floor`, `ceil`, `frexp`, `ldexp`). For bare-metal targets define
  `AL_WRITE(fd, ptr, len)`.
- Expressions are lowered to statements so the ALang evaluation order is preserved exactly; all
  checks (overflow, bounds, contracts, loop limits) are kept, with identical trap messages.
- Number formatting is implemented in the runtime prelude (including an exact big-number float
  formatter), so output is byte-identical to the interpreter.

Recommended: `cc -std=c99 -O2 file.c -lm`. The test suite checks gcc and clang at `-O0` to `-O3`.
