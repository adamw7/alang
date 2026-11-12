# ALang Language Specification (v0.2)

ALang is a small, statically typed language designed to be **written by AI agents** and **run safely**.
Four properties drive every rule:

1. **No heap.** Every value has a compile-time size and lives on the stack or in static data. There is
   no `new`, pointer, `null`, garbage collector or allocator.
2. **Deterministic.** Output is a pure function of the source and the input (the tool arguments, or the
   standard input of `fn main`): the same bytes and the same trap on every machine, compiler and locale.
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
  `limit`, `step`, `rev`, `test`, `tool`, `import` and `result` are contextual and may be used as names.
- The names of the builtin functions, the standard library included (§9), cannot be redefined (E0108),
  but may be variable names.

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
| `(T1, T2, ...)` | a tuple of two or more values, read as `t.0`, `t.1`, ... | as a struct of those fields |
| `map[K, V; N]` | up to N entries from distinct keys to values, in key order | 4 + N × (size(K) + size(V)) (aligned) |
| `set[T; N]` | up to N distinct values, in order | as `list[T; N]` |
| `Name` | a declared `struct` or `enum` | sum of fields / tag + largest payload |

`N` is a compile-time integer constant (a literal or a `const`) from 1 to 1 048 576; only in the type of
a function parameter may it be `_`, any capacity (§5). A function's type parameters (`fn first<T>(...)`) and
function types (`fn(i64) -> bool`) also appear only in its parameters (§5). There are no pointer, reference
or dynamically sized types.

All types are **values**: assigning, passing, returning, storing in a field or element, and iterating
all copy, so two variables never share storage (except through a `mut` parameter, §5).

**Assignability.** A value of type `A` can be stored where `B` is expected only if `A` and `B` are the
same type, or both are `str[M]`/`str[N]`, or both are `list[T; M]`/`list[T; N]`, with `M <= N`
(widening). Everything else needs an explicit conversion: `str[N](s)` and `list[T; N](xs)` give a str or
list of capacity N, larger or smaller, and trap (R0004) when the value does not fit (§6). Arrays never
convert to lists, but an array **literal** `[a, b, c]` (or `[v; n]`) can initialize a list with enough
capacity, and `[]` is the empty list.

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

**Tuples** group values without declaring a struct: `(1, "a")` is a `(i64, str[1])`, and a literal takes the
tuple type its context expects, `let p: (str[8], i64) = ("ann", 7);`. Elements are read and written by position,
`p.0`, `p.1` (and `t.0.1` for an element of an element), and `let (name, n): (str[8], i64) = p;` takes a tuple
apart into new variables, with `_` for an element that is not needed. A function returns several values as a
tuple: `fn divmod(a: i64, b: i64) -> (i64, i64)`. Tuples compare with `==` element by element, and `sort` orders
them so; tuple types are equal only when every element type is (no widening, except in a literal).

**Maps and sets** have a capacity, like lists, and keep their keys (a set its values) in the order of `sort`
(§9.5), whatever order they were added in, so iterating, printing and comparing them never depends on
history. `[]` is an empty one; `[("a", 1), ("b", 2)]` builds a map (a key given twice keeps its last value) and
`[3, 1, 3]` a set (`{1, 3}`). `m[k]` reads the value of `k` and traps (R0003) if there is none; `m[k] = v` sets it,
adding `k` if it is new, which traps (R0004) when the map is full; `m[k] += 1` and `m[k].field = x` need `k` to be
there. `for k in m` goes through the keys, `for k, v in m` through the keys with their values, `for x in s`
through a set, all in order (`rev` backwards). They print as `{"a": 1, "b": 2}` and `{1, 3}`, compare with `==`
by contents (capacities may differ), and finding a key takes a binary search.

**Enum** variants are always qualified (`Shape.Empty`, `Shape.Circle(2.0)`), and payloads are read only
with `match`. There is no `null`: model absence with an enum such as `enum MaybeI32 { None, Some(i32) }`.

**Equality and ordering.** `==` and `!=` work on every type (strings, and lists of the same element type,
of different capacities may be compared; aggregates compare element-wise). `< <= > >=` work on numbers of
the same type only. Floats follow IEEE 754: `NaN != NaN`, `-0.0 == 0.0`.

## 3. Declarations

A file is a sequence of imports (§3.1), then declarations, in any order (everything can refer to
everything):

```
import "path/to/file.al";
const NAME: Type = constant-expression;
struct Name { field: Type, ... }
enum Name { Variant, Variant(Type, ...), ... }
fn name(param: Type, param: mut Type) -> ReturnType requires cond ensures cond { ... }
tool fn name(param: Type) -> ReturnType { ... }
test "description" { ... }
```

- **Constants** are evaluated at compile time from literals, other constants, operators, conversions,
  struct, enum and array literals, and the builtins that only compute: all but `print`, `push`, `pop`,
  `clear`, `append`, `panic`, `sort`, `reverse`, `insert` and `remove` (`const MASK: u32 = pow(2, 12) - 1;`).
  User function calls are not allowed (E0209). There are no global variables.
- **`fn main()`** returns nothing; `alang run` calls it. It takes no parameters, or one read-only
  `str[N]` (`fn main(input: str[4096])`), which receives all of the program's standard input as bytes:
  `alang run prog.al < data.txt`. Input longer than N bytes traps (R0004) before main runs; no input is
  an empty str. Any other signature is E0602. The program never reads a file itself: the host decides
  what the input is, and the same input gives the same output.
- **`tool fn`** is callable with JSON arguments (§10) and cannot take `mut` parameters.
- **`test "..."`** blocks are run by `alang test`, each from a fresh state.

Names are unique: two items cannot share a name, and **locals cannot shadow** (E0105): a local may not
reuse the name of a local still in scope, a parameter, a constant or a type. Sibling blocks may reuse
names.

### 3.1 Imports

`import "helpers.al";` makes the declarations of another file part of the program. Imports come first in
a file, before any declaration (E0001), and:

- **The path is relative to the importing file**, uses `/` between directories on every OS, and names a
  `.al` file: `import "lib/text.al";`, `import "../shared/geometry.al";`. Absolute paths and backslashes
  are E0702, so a program means the same wherever it is copied. A file that cannot be read, or is not
  UTF-8, is E0701.
- **One namespace.** The imported constants, structs, enums and functions are used by their plain names,
  as if declared in the importing file, and every name in the program is unique (E0104 names both files).
  There are no modules, qualified names or private declarations.
- **Each file once.** A file reached through several imports is read once; importing the same file twice
  from one file is E0702. Files may not import each other, directly or through others (E0703, the import
  cycle).
- **An imported file keeps its `fn main` and its tests**, which are left out of the importing program, and
  its `tool fn`s are plain functions there: a program's entry points and tools are those of the file given
  on the command line. So a library can have a demo main and tests of its own
  ([examples/helpers.al](../examples/helpers.al) has both, and [examples/tally.al](../examples/tally.al)
  imports it).
- **Diagnostics and traps name their file.** In text, `--> lib/text.al:12:5`; in JSON, a diagnostic or
  trap in an imported file has a `"file"` key (one in the file given on the command line does not, as the
  report names that file once), and a call in an imported file is shown as `called at lib/text.al:12:5`.
  Diagnostics are sorted by file, the main file first, then by position.

## 4. Statements

| Statement | Form |
|-----------|------|
| binding | `let name: Type = expr;` (immutable), `var name: Type = expr;` (mutable), `let (a, b): (T1, T2) = tuple;` |
| assignment | `place = expr;` and `+= -= *= /= %=` |
| condition | `if c { } else if c { } else { }` |
| loops | `while c limit n { }`, `for i in a..b { }` (half-open), `for i in a..=b { }` (inclusive), either with `step k`, and `rev` before either for the same values last first; `for x in xs { }` (array, list or str: `u8` bytes), `for i, x in xs { }` (with each index), `for x in rev xs { }`; `for p in split(s, sep) { }`, `for w in words(s) { }`, `for c in chars(s) { }` |
| labels | `outer: for ... { }` or `outer: while ... { }`, then `break outer;` or `continue outer;` from a loop inside it |
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
  (`i64` for literals). `a..=b` includes `b`. `step k` (`k` of the same type, evaluated once after the bounds)
  counts by `k`: `0..10 step 3` is 0, 3, 6, 9; a step that is not positive traps (R0013), and a literal one is
  E0202. `rev` takes the same values, the last one first: `rev 0..10 step 3` is 9, 6, 3, 0, and to count down
  from 10 to 1 write `rev 1..=10`. Nothing overflows at the ends of a type: `for b in u8(250)..=u8(255)` stops
  after 255. `for x in xs` goes through what `xs` held before the first iteration: changing
  `xs` in the loop does not affect it. (Only a variable that the body changes, by assigning to it or passing
  it as `mut`, is copied for this, §7; any other collection is used where it is.)
- `for i, x in xs` also gives each element's position, an `i64`, as `i` (for split, words and chars, the
  number of the piece or code point). `for x in rev xs` goes through an array, list or str from its last
  element; `i` is then still the position, counting down.
- `break label;` and `continue label;` leave or continue the loop labelled `label:` around them, from any
  depth; `break;` and `continue;` the innermost loop. Labels of loops inside one another differ (E0105).
- `for p in split(s, sep)` and `for w in words(s)` go through the pieces of `s` one at a time (§9.4),
  without building a list: each piece is a `str` of the capacity of `s`, which no piece can outgrow, so the
  loop never traps on the size or number of the pieces. `s` and `sep` are evaluated once, before the first
  piece, like a collection. Narrow a piece with `str[N](p)` after checking `len(p)`.
- Loop variables, match bindings and `let` bindings are immutable.
- Only calls may be expression statements (E0305); ignoring a non-void result is warning W0302.
- A program without errors is also checked for work that is lost: a local or match binding that is never
  used (W0304; write `_` for a binding), a variable that is changed but never read (W0305), often a copy
  such as `var it: Item = items[0]; it.qty += 1;` that leaves `items[0]` as it was, and a function of the
  checked file that nothing calls (W0306). Names that start with `_` are left alone.
- A function with a return type must return on every path; loops never count as returning (E0301).

**Match** patterns: `Enum.Variant`, `Enum.Variant(a, b)` (bindings; `_` ignores one), integer and
character literals (`3`, `-1`, `'x'`), string literals, `true`, `false`, and `_` (anything; must be
last). Each arm body is a block. Match works on enums, integers, `bool` and `str`, and must be
exhaustive (E0306): all enum variants or both bools, or a final `_`, which integers and strings always
need. Duplicate or unreachable arms are E0307.

An arm may have a **guard**: `Shape.Circle(r) if r > 10.0 => { }` matches only when the pattern does and then the
`bool` condition, which can use the arm's bindings, holds; otherwise matching goes on with the next arm. A guarded
arm covers nothing for exhaustiveness, so a match whose last arms are guarded still needs an unguarded one (often
`_`) after them, and a guarded arm below an unguarded one with the same pattern is unreachable (E0307).

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
  same call that is an aggregate or is itself passed as `mut`: `append(mut s, s)` is rejected, and so is
  `swap(mut xs[i], mut xs[j])`, since `i` and `j` may be equal; copy first. A read-only number or bool
  is a copy made before the call, so `remove(mut xs, len(xs) - 1)` is fine.
- **Mutating calls are statements (E0215):** a call with a `mut` argument is a statement of its own or the
  whole right-hand side of `let`/`var`/assignment/`return`, so every mutation is at a visible point.
- **No recursion (E0401):** the call graph, contracts included, must be acyclic. Use a loop and a
  fixed-capacity work list.

**Any capacity.** A `_` in place of a capacity or length in the type of a parameter, as in
`fn total(xs: list[i64; _])`, `fn shout(s: mut str[_])` or `fn longest(rows: [str[_]; _])`, makes the
function take a str, list or array of any capacity there; each `_` stands for its own. A call fixes them
from its arguments: a read-only argument gives its capacity, and a `mut` one must have exactly the
parameter's type apart from the `_`s. Element types never change: a `list[i32; 4]` does not fit a
`list[i64; _]` (E0201, E0207).

Inside the function the capacity is not known. `cap(s)` is the caller's, and `push` and `append` trap
(R0004) when it is full, as they would in the caller. To keep the value in a variable, field or return
value, which have constant capacities, convert it, `str[64](s)`, which traps (R0004) if it is longer; only
an empty value (`""`, `[]`) or one of the same parameter can be assigned to it. The checker checks the
function once, as written; each call with new capacities then runs a copy of it made for them, an
**instance**, which the interpreter runs, the C backend compiles and `alang check --memory` lists:
`total(list[i64; 4])`. A `_` anywhere else is E0217: in a variable, field, return type, constant or
conversion, in a parameter of a `tool fn` (its JSON schema states the capacity) and in the input of `main`.

```
fn total(xs: list[i64; _]) -> i64 {
    var sum: i64 = 0;
    for x in xs {
        sum += x;
    }
    return sum;
}
```

**Type parameters.** `fn largest<T>(xs: list[T; _]) -> T` takes a list of any element type. Inside, `T` is a type
of its own, which values can be copied, stored, compared with `==` and `!=`, ordered with `compare(a, b)` (the order
of `sort`; `<` is only for numbers and strs) and printed; anything else, such as `a + b`, is an error until a call
says what `T` is. Each type parameter must appear in a parameter's type, and a call fixes it from the arguments:
the first argument whose type gives it (arguments with types of their own before literals, so that
`largest_of(x, 1)` with `x: u8` makes the `1` a `u8`), which the others must then match exactly. Like a `_`, each
call with new types runs an instance made for them, checked again with the types filled in (`alang check
--memory` lists them). Structs and enums have no type parameters; tuples and the built-in collections cover most
of what they would.

**Function parameters.** A parameter of a function type, `keep: fn(T) -> bool`, takes a function by its name,
`count_if(xs, is_even)`, and the body calls it, `keep(x)`. The function's parameter and result types must be the
parameter's exactly (type parameters aside, which they can fix), it cannot have `mut` parameters, and it cannot be
a builtin (wrap one in a function of your own) or itself take a `_`, type parameter or function. A parameter of a
function type can be passed on to another function, but not stored, returned or put in a variable: there are no
closures. Each call with a new function runs an instance that calls that function directly, so the call graph
stays known, and passing a function to something that calls it back is recursion (E0401).

```
fn count_if<T>(xs: list[T; _], keep: fn(T) -> bool) -> i64 {
    var n: i64 = 0;
    for x in xs {
        if keep(x) {
            n += 1;
        }
    }
    return n;
}
```

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
- `< <= > >=` take two numbers of the same type, or two strs of any capacities, ordered byte by byte
  (unsigned), a prefix first: the order of `sort`, C's `strcmp` and `<` on strs in Python or Rust.
- Integer arithmetic is **checked**: overflow traps (R0001), division or remainder by zero traps (R0002).
  Where wrapping around is wanted, as in a hash, say so: `wrapping_mul(h, k)`; `saturating_add(a, b)` stops at
  the type's limits instead (§9.2).
  Division truncates toward zero; `%` has the dividend's sign; `MIN / -1` overflows and `MIN % -1` is `0`.
- Shifts: the left operand gives the type; the amount may be any integer type and must be in
  `0..bits-1` (R0012). Bits shifted out are discarded. `>>` is arithmetic for signed types, logical for
  unsigned ones. `& | ^ ~` take integers of the same type.
- `and or not` take `bool`; `and`/`or` short-circuit.
- Float arithmetic is IEEE 754, round-to-nearest-even, without fused multiply-add. Division by zero gives
  `inf`/`nan`, never a trap.

**Conversions** are calls to a primitive type: `i64(x)`, `u8(n)`, `f64(count)`; and to a capacity:
`str[16](s)`, `list[i64; 8](xs)`.

| From → To | Behavior |
|-----------|----------|
| integer → integer | value preserved; out of range traps (R0009) |
| integer → float | rounded to nearest (ties to even) |
| float → integer | truncated toward zero; NaN or out of range traps (R0009) |
| f64 → f32 | rounded to nearest; may become `inf` |
| f32 → f64 | exact |
| bool → integer | `true` → 1, `false` → 0 |
| str → integer | decimal text: an optional `+` or `-`, then digits; other text or out of range traps (R0009) |
| str → f64 | decimal text: an optional `+` or `-`, then digits with at most one `.` among them (`2.5`, `-0.5`, `3.`, `.25`; no exponent); the nearest f64. It converts when the digits from the first to the last that is not 0 make a number of at most 2^53, between about 1e-22 and 1e37 in size, so that one correctly rounded operation gives that f64 on every backend: 15 significant digits always do. Other text traps (R0009); `is_float(s)` says first. For an f32, `f32(f64(s))`. |
| anything → bool | not allowed (E0214); compare instead |
| `str[M]` → `str[N]` | the same bytes; more than N bytes traps (R0004) |
| `list[T; M]` → `list[T; N]` | the same elements, of the same type T; more than N traps (R0004) |

**If expressions.** `if c then a else b` is `a` when the `bool` `c` holds and `b` otherwise; only that branch is
evaluated, so `if len(xs) > 0 then xs[0] else 0` never indexes an empty list. The branches have one type, as
the operands of `+` do (literals take the other branch's type or the expected one), and a str or list branch
widens to the larger capacity. An if expression is a whole expression, never an operand:
`(if c then 1 else 2) + 3`, and `else if c2 then ...` chains. A call with `mut` arguments cannot be a branch
(E0215).

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
  statements (literals, call results, copies, the copy a `for x in xs` loop makes of a variable its body
  changes), so the figure is an upper bound: temporaries of different statements are added up, because
  the C backend gives each its own variable and a C compiler need not reuse a dead one's stack slot. Two
  values are counted once, as they are built where they are kept: what `split`, `words`, `join` and
  `replace` build for the variable a `let`/`var` declares, and the pieces of `for p in split(s, sep)`, made
  one at a time in the loop variable. `alang check` prints the bound, and
  `--memory` prints it per function.
- Every command checks the program against a **memory budget**: 8 MiB (8 388 608 bytes) unless
  `--max-stack BYTES` sets another (`0`: none). An entry point (`main`, a tool or a test) whose worst-case
  stack exceeds it is E0402. Constants are evaluated while checking, so they are checked first: together,
  with the values their initializers build, they must fit the same budget (E0405). A program that passes
  cannot exhaust the interpreter's memory and fits the default C stack of Linux and macOS. Windows
  reserves less by default (1 MiB with MSVC, 2 MiB with MinGW): link with `-Wl,--stack,8388608`
  (`/STACK:8388608` with MSVC), or check against the smaller stack with `--max-stack`.
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
  inputs are a tool call's arguments and the standard input that `alang run` passes to a
  `fn main(input: str[N])`, read whole before main starts. Imports are read when the program is checked,
  relative to the importing file, so they add no input either.
- **No undefined or implementation-defined behavior:** integer widths are fixed, and overflow, bad
  indexes, shifts and conversions, and failed contracts trap with a fixed code, message and location.
  Evaluation order is fixed (§6).
- **Portable floats:** every float operation is built from correctly rounded IEEE 754 operations (`+ - * /`,
  `sqrt`, `floor`, `ceil`, `round`, `trunc`, `abs`, `min`, `max`, `clamp`, conversions). Math libraries round
  `sin`, `exp`, `pow` and the like differently, so ALang computes them itself (§9.2), with the same operations
  in the same order in the interpreter and the C backend. `min`/`max` are `if b < a then b else a` / `if b > a then b else a`, which
  fixes NaN handling. The C backend refuses `-ffast-math` and x87 excess precision, and disables fused
  multiply-add contraction.
- **Exact text formatting** (§9.6), independent of locale and C library.
- **A deterministic step budget:** the interpreter counts executed statements, and `--max-steps`
  (default 100 000 000) stops a runaway program with R0011 after exactly the same number of steps.
- **Deterministic tooling:** diagnostics are sorted by file and position, JSON has a fixed key order, generated C
  is byte-identical for identical input, and nothing depends on the host locale or line endings.

The test suite runs every program repeatedly and under several locales, and compares the C backend byte
for byte with the interpreter under several compilers and optimization levels.

## 9. Builtins and the standard library

Builtin functions come with the language. They are polymorphic where a user function cannot be (a user
function takes any capacity, §5, but not any element type): `len` takes any array, list or str, `sort`
any list. The core builtins print and change
lists and strings; the rest are the standard library: math, characters, text and collections. Each
behaves the same in the interpreter and the C backend, traps and messages included, and each checks its
arguments in a fixed order, so the same bad call always gives the same trap. `alang explain --all` lists
them all.

### 9.1 Core

| Builtin | Description |
|---------|-------------|
| `print(values...)` | Writes the text of each value (no separator) and a newline. |
| `len(x) -> i64` | Elements of an array/list, bytes of a str. |
| `cap(x) -> i64` | Capacity N of a `list[T; N]` or `str[N]`. |
| `push(mut xs, v)` | Appends an element (a `u8` byte for str). Traps if full (R0004). |
| `pop(mut xs) -> T` | Removes and returns the last element. Traps if empty (R0012). |
| `clear(mut xs)` | Removes all elements. |
| `append(mut s, values...)` | Appends the text of each value to a str. Traps if it does not fit (R0004). |
| `panic(message)` | Stops with trap R0010. |

### 9.2 Math

| Function | Description |
|----------|-------------|
| `abs(x)`, `min(a, b)`, `max(a, b)` | Same-type numbers. `abs` of the minimum integer traps (R0001). |
| `clamp(x, lo, hi) -> T` | `lo` if `x < lo`, `hi` if `x > hi`, else `x`; same-type numbers. Traps (R0013) unless `lo <= hi`, so a NaN bound traps. |
| `pow(x, n) -> T` | `x` (any integer type) to the power `n` (any integer type); `pow(0, 0)` is 1. Traps if `n < 0` (R0013) or the result does not fit (R0001). For an `f32`/`f64` `x`, `n` is a float of the same type (`pow(2.0, 0.5)`, `pow(x, 3.0)`) and nothing traps: the special cases are C99's (`pow(-8.0, 0.5)` is NaN, `pow(0.0, -1.0)` is `inf`), an integer `n` gives the correctly rounded power (`pow(2.0, 10.0)` is `1024.0`), and `pow(x, 0.5)` is `sqrt(x)`. |
| `gcd(a, b) -> T` | Greatest common divisor of same-type integers, never negative; `gcd(0, 0)` is 0. `gcd(x, 0)` for the minimum `x` does not fit and traps (R0001). |
| `lcm(a, b) -> T` | Least common multiple, never negative; 0 if either is 0. Traps if it does not fit (R0001). |
| `wrapping_add(a, b)`, `wrapping_sub(a, b)`, `wrapping_mul(a, b)` | Same-type integers; the exact result wrapped around to the type (modulo 2^bits), so they never trap: `wrapping_add(u8(250), 10)` is `4`, `wrapping_mul(h, 16777619)` is the multiply of 32-bit FNV. |
| `saturating_add(a, b)`, `saturating_sub(a, b)`, `saturating_mul(a, b)` | Same-type integers; the exact result, or the type's largest or smallest value when it does not fit: `saturating_sub(u8(3), 5)` is `0`. Never trap. |
| `sqrt(x)`, `floor(x)`, `ceil(x)` | On `f32`/`f64`, correctly rounded. |
| `round(x)`, `trunc(x)` | On `f32`/`f64`, exact: `round` halves away from zero (`round(2.5)` is `3.0`, `round(-0.5)` is `-1.0`); `trunc` drops the fraction (`trunc(-2.7)` is `-2.0`). |
| `exp(x)`, `log(x)`, `log2(x)`, `log10(x)` | On `f32`/`f64`: e^x and the logarithms; `log` of 0 is `-inf` and of a negative number NaN. `log2` of a power of two and `log10` of a power of ten are exact. |
| `sin(x)`, `cos(x)`, `tan(x)` | On `f32`/`f64`, in radians, for every finite `x` (a large argument is reduced exactly). |
| `asin(x)`, `acos(x)`, `atan(x)`, `atan2(y, x)` | On `f32`/`f64`, in radians: `asin` and `atan` from -pi/2 to pi/2, `acos` from 0 to pi (NaN outside -1..1), `atan2` the angle of the point (x, y) from -pi to pi. |
| `is_nan(x)`, `is_finite(x)` | On `f32`/`f64`. |

The elementary functions are within one unit in the last place of the exact result (`tan` within two), and the
same bits everywhere: ALang computes them itself (`exp`, `log`, `log10`, `sin`, `cos`, `asin`, `acos`, `atan` and
`atan2` as fdlibm does, see THIRD-PARTY-NOTICES), and an `f32` result is the `f64` one rounded once.

A literal argument takes the type of the other arguments or, for `min`, `max`, `clamp`, `pow`, `gcd`,
`lcm` and the wrapping and saturating functions, of the variable the result is stored in: `let b: u8 = pow(2, 7);`.

### 9.3 Characters

On one byte, a `u8` such as `s[i]`, a loop variable of `for c in s`, or a character literal; ASCII only,
so every other byte is not a letter, digit or space and is left as it is.

| Function | Description |
|----------|-------------|
| `is_digit(c)`, `is_alpha(c)`, `is_alnum(c)` | `0-9`; `a-z` and `A-Z`; either. |
| `is_upper(c)`, `is_lower(c)` | `A-Z`; `a-z`. |
| `is_space(c)` | Space, `\t`, `\n`, `\r`, `\x0b` or `\x0c`. |
| `to_upper(x)`, `to_lower(x)` | A `u8`, or every byte of a `str[N]`, with its case changed; the same type back. |

### 9.4 Text

What `contains` and `index_of` look for in a str, a prefix or suffix, a separator, and what `replace`
replaces or puts in, is a **pattern**: a str, or one `u8` byte (`split(line, ',')` and
`split(line, ", ")` both work).

| Function | Description |
|----------|-------------|
| `contains(xs, x) -> bool` | In a str, whether the pattern `x` occurs (the empty str always does); in an array or list, whether an element `== x`. |
| `index_of(xs, x) -> i64` | Where the first occurrence starts, as for `contains`, or -1. |
| `count(xs, x) -> i64` | In a str, the non-overlapping occurrences of the pattern `x`, left to right (`count("aaaa", "aa")` is 2); traps (R0013) if `x` is an empty str. In an array or list, the elements `== x`. |
| `starts_with(s, p) -> bool`, `ends_with(s, p) -> bool` | Whether the str `s` begins or ends with the pattern `p`. |
| `slice(xs, from, to) -> T` | A copy of the elements `from..to` (half-open) of a str or list, of the same type, or of an array, as a `list[T; N]`. Traps (R0003) unless `0 <= from <= to <= len(xs)`. |
| `trim(s) -> str[N]` | `s` without the ASCII whitespace at its start and end. |
| `split(s, sep) -> list[str[K]; M]` | The pieces of `s` between occurrences of the pattern `sep`, left to right, empty pieces included: `split("a,,b", ",")` is `["a", "", "b"]`, `split("", ",")` is `[""]`. Traps if `sep` is empty (R0013). |
| `words(s) -> list[str[K]; M]` | The pieces of `s` between runs of ASCII whitespace; never empty pieces. |
| `join(parts, sep) -> str[N]` | The strs of an array or list, with the pattern `sep` between them. |
| `replace(s, from, to) -> str[N]` | `s` with every occurrence of the pattern `from`, left to right and not overlapping, replaced by the pattern `to`. Traps if `from` is empty (R0013). |
| `try_split(mut parts, s, sep) -> bool` | `split(s, sep)` into the list `parts`, replacing what it held: true if every piece fits, otherwise false with `parts` left empty, never a trap. |
| `try_words(mut parts, s) -> bool` | `words(s)` into the list `parts`, as `try_split` does. |
| `is_int(s) -> bool` | Whether `i64(s)` would succeed: decimal text that fits an `i64`. |
| `is_float(s) -> bool` | Whether `f64(s)` would succeed: decimal text that converts exactly (§6). |
| `fixed(x, n)`, `pad_left(v, w, fill)`, `pad_right(v, w, fill)` | Formatted text (§9.6). |

**Results typed by where they are stored.** The size of what `split`, `words`, `join` and `replace`
(and `fixed`, `pad_left` and `pad_right` when they are not written out at once, §9.6) build depends on the data, so each takes its type, capacities included, from the variable it initializes
or is assigned to, or from the function's return type. The call must be the whole right-hand side of a
typed `let`/`var`, a constant, an assignment with `=`, or a `return`, or for `split` and `words`, what a
`for` loop goes through; anywhere else is E0216. A result that does not fit traps (R0004): `split` and
`words` check each piece in turn, first that the list has room for it, then that it fits a `str[K]`;
`join` and `replace` check the whole length first.

**Text that may not fit.** Input is untrusted, and one overlong line should not stop a program. A loop
over the pieces never traps on their size or number, so it can skip or report what does not fit, and it
builds no list, so it scales to an input of any size the program accepts:

```alang
/// Sums the integers on the lines of `text`; lines too long or not integers are counted as bad.
tool fn sum_lines(text: str[4096]) -> [i64; 2] {
    var total: i64 = 0;
    var bad: i64 = 0;
    for line in split(text, '\n') {
        if len(line) > 20 {
            bad += 1;
            continue;
        }
        let t: str[20] = trim(str[20](line));
        if is_int(t) {
            total += i64(t);
        } else if len(t) > 0 {
            bad += 1;
        }
    }
    return [total, bad];
}

test "an overlong line is counted, not trapped on" {
    assert sum_lines("1\n2\n999999999999999999999999999999\nx\n") == [3, 2];
}
```

To keep the pieces in a list, use `try_split` (or `try_words`): it returns false, and leaves the list
empty, when they do not fit, instead of trapping. `split` or `words` of text that is not a constant,
stored in a list its pieces may not fit, is warning W0307: a `str[K]` splits into up to K / L + 1 pieces
on a separator of L bytes, and into up to (K + 1) / 2 words, each of up to K bytes.

```alang
/// The second field of each "a,b,c" line; lines with another number of fields are left out.
tool fn second_fields(text: str[1024]) -> list[str[16]; 32] {
    var out: list[str[16]; 32] = [];
    for line in split(text, '\n') {
        var fields: list[str[16]; 3] = [];
        let ok: bool = try_split(mut fields, line, ',');
        if ok and len(fields) == 3 and len(out) < cap(out) {
            push(mut out, fields[1]);
        }
    }
    return out;
}

test "lines that do not fit are left out" {
    assert second_fields("a,b,c\nd,e\nf,g,h,i\nj,k,l") == ["b", "k"];
}
```

**Text to numbers.** A conversion from a str to an integer type (§6) reads decimal text: an optional `+`
or `-`, then at least one ASCII digit, and nothing else, so `trim` it first. Other text, or a value out of
the type's range, traps (R0009). `is_int(s)` says whether `i64(s)` succeeds; for a narrower type, convert
to `i64` and check the range. `f64(s)` reads a decimal number such as `19.99` (§6), and `is_float(s)`
says whether it succeeds.

```alang
/// Sums the integers in a comma-separated list, skipping empty items; -1 if an item is not an integer.
tool fn sum_csv(line: str[256]) -> i64 {
    var total: i64 = 0;
    for item in split(line, ',') {
        let t: str[256] = trim(item);
        if len(t) == 0 {
            continue;
        }
        if not is_int(t) {
            return -1;
        }
        total += i64(t);
    }
    return total;
}

fn main() {
    print(sum_csv("1, 2,, 40 "));
}

test "empty items are skipped and bad ones reported" {
    assert sum_csv("1, 2,, 40 ") == 43;
    assert sum_csv("1, two") == -1;
}
```

Text is bytes, and these read and write it as UTF-8:

| Function | Description |
|----------|-------------|
| `is_utf8(s) -> bool` | Whether `s` is well-formed UTF-8: no overlong forms, surrogates, code points past U+10FFFF or cut-off sequences. |
| `char_count(s) -> i64` | The number of code points in `s` (`len` counts bytes). |
| `chars(s) -> list[u32; N]` | The code points of `s`; its type is that of the variable it is stored in, and too many trap (R0004). `for c in chars(s) { }` goes through them one at a time instead, each a `u32`, and never traps. |
| `push_char(mut s, c)` | Appends the UTF-8 encoding of the code point `c` (any integer type), 1 to 4 bytes. Traps (R0013) unless `c` is 0 to 0x10FFFF and not a surrogate, and (R0004) if it does not fit. |
| `slice_chars(s, from, to) -> str[N]` | The code points `from..to` of `s`, as a str of the capacity of `s`; never cuts a character in half. Traps (R0003) unless `0 <= from <= to <= char_count(s)`. |

A byte that does not start a well-formed sequence is one code point of its own, U+FFFD (65533), so every
byte belongs to exactly one code point and the same bytes always decode the same way. Case changes and
the character classes (`is_alpha`, `to_upper`, ...) stay ASCII.

### 9.5 Collections

| Function | Description |
|----------|-------------|
| `sort(mut xs)` | Sorts a list, array or str in place, ascending, in the order below. |
| `reverse(mut xs)` | Reverses a list, array or str in place. |
| `insert(mut xs, i, v)` | Inserts `v` into a list (a `u8` into a str) at position `i`, moving the rest up. Traps unless `0 <= i <= len(xs)` (R0003), then if full (R0004). |
| `remove(mut xs, i) -> T` | Removes and returns the element at position `i` of a list (a byte of a str), moving the rest down. Traps unless `0 <= i < len(xs)` (R0003). Like `pop`, it may be a statement of its own. |

| `insert(mut m, k, v)`, `insert(mut s, x)` | Sets `k` to `v` in a map (as `m[k] = v`), or adds `x` to a set; a key or value already there is kept once. Traps (R0004) when a new one does not fit. |
| `remove(mut m, k) -> bool`, `remove(mut s, x) -> bool` | Removes a key (with its value) or a value; whether it was there. |
| `get_or(m, k, d) -> V` | The value of `k` in the map `m`, or `d` when there is none. |

| `compare(a, b) -> i64` | -1, 0 or 1 as `a` comes before, equals or comes after `b` in the order below; two values of any one type, so it orders the values of a type parameter. |

`len`, `cap`, `clear` and `contains` work on maps and sets too, by key. `contains`, `index_of`, `count` and `slice` (§9.4) work on arrays and lists too. Positions (`i`, `from`, `to`) may
be of any integer type, as indexes may.

**The order of `sort`** is defined for every type: numbers by value, with `-0.0` before `0.0` and every
NaN after everything else; `false` before `true`; strs (by unsigned byte), arrays and lists element by
element, a prefix before a longer value (sets too; maps entry by entry, key then value); tuples and structs field by field, in the order they are declared; enums
by the order of their variants, then by payload. So a list of structs sorts by its first field, then its
second: put the key first, and sort on a negated key, or `reverse` afterwards, for descending order. Two
values this order calls equal cannot be told apart, so the result is the same whatever the algorithm,
and no sort is stable or unstable in a way a program can see.

### 9.6 Text formatting

The text of a value (for `print`, `append`, trap messages and the left/right values of a failed
`assert`):

- integers in decimal; `bool` as `true`/`false`;
- floats rounded to 6 decimal places (round-half-even on the exact binary value), trailing zeros removed
  but one decimal kept: `1.5`, `2.0`, `0.333333`, `100000000000000000000.0` for `1e20`, `-0.0`, `nan`,
  `inf`, `-inf`;
- a top-level `str` as its raw bytes; a `str` inside an aggregate quoted with escapes: `"a\n"`;
- arrays and lists as `[1, 2, 3]`, tuples as `(1, "a")`, structs as `Point(x: 1.0, y: 2.0)`, enums as
  `Shape.Empty` or `Shape.Circle(2.0)`;
- a `u8` (a character literal too) as a number: add a character to a string with `push(mut s, 'a')`;
  `append(mut s, 'a')` appends `97` (warning W0303).

Three functions format text with a width or a number of decimals:

| Function | Text |
|----------|------|
| `fixed(x, n)` | The f32 or f64 `x` with exactly `n` digits after the point, 0 to 20 (otherwise R0013), and no point for 0: its exact binary value rounded half to even, as above. `fixed(3.14159, 2)` is `3.14`, `fixed(2.5, 0)` is `2`, `fixed(2.675, 2)` is `2.67` (2.675 is stored as 2.67499...); `nan`, `inf`, `-inf`. |
| `pad_left(v, w, fill)` | The text of the number, bool, str or `fixed(...)` `v`, as `print` writes it, with the `u8` byte `fill` in front up to `w` bytes; longer text is kept whole. `pad_left(7, 3, '0')` is `007`, `pad_left(-42, 6, '0')` is `000-42`. A width above 1 048 576 traps (R0013). |
| `pad_right(v, w, fill)` | The same with the fill after the text: `pad_right("ab", 4, '.')` is `ab..`. |

They build no str when they are an argument of `print` or `append`, or the value `pad_left` or
`pad_right` pads: the text is written straight out, `append(mut s, "$", c / 100, ".", pad_left(c % 100, 2, '0'))`.
Anywhere else they take the type of a typed str they are stored in, as `join` does (§9.4), and trap
(R0004) when the text does not fit it: `let cell: str[8] = pad_left(n, 6, ' ');`. Elsewhere they are
E0216. Their arguments are evaluated and checked before any of the text is written.

## 10. Tools: calling ALang from an agent

A `tool fn` can be called with JSON; only those of the file given on the command line are tools, not
those of the files it imports (§3.1). `alang tools file.al` prints Anthropic-style tool definitions
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

`input_examples` shows the model up to three distinct argument objects, taken from the calls of the tool
in `test` blocks whose arguments are all constant expressions, in the order the tests make them. Each call
is run first (within `--max-steps`), and only one that returns a result JSON can represent becomes an
example, so every example passes the contract and therefore fits the schema. A tool that no test calls
that way has no `input_examples`.

`alang call file.al name '{"arg": ...}'`
runs one call and prints `{"ok": true, "result": ..., "stdout": "...", "steps": N}` or
`{"ok": false, "error": {...}}`. With `-` in place of the JSON the arguments are read from stdin (as
UTF-8; other bytes are an error); with nothing they are `{}`. `stdout` keeps the first 1 MiB of output;
past that, `"stdout_truncated": true` and `"stdout_bytes"` (the full length) are added. `steps` is the
number of statements the call executed (as for `--max-steps`), a cost that is the same on every run;
a call that ran into a trap or `bad_result` reports it too, one refused before it ran does not.

`alang fuzz file.al` calls every tool, and a `fn main(input: str[N])` with its input, with arguments at
the limits of the schema: the plainest arguments (those of the first `input_examples`, if any), then one
parameter at a time over values of its type within its bounds: the smallest and largest numbers, `0`, `1`
and `-1`; empty and full strs of many shapes (one long word, words that all differ separated by spaces,
commas, semicolons, tabs or lines, "name number" and "key: value" lines, rows of 1 to 6 fields, numbers,
separators alone); empty, one-element and full lists and arrays; every enum variant; each struct field in
turn. It reports each distinct trap, by code and place, with the first arguments that hit it, and exits
with 1 if there is one. A failed `requires` of the tool itself is the caller's fault and is left out; one
of a function it calls is not. The calls are the same on every run, at most 200 per tool, each within
`--max-steps`.

| ALang type | JSON |
|------------|------|
| `bool` | boolean |
| integers | integer, range-checked (`minimum`/`maximum` in the schema) |
| floats | number; JSON has no NaN or infinity, so a result holding one fails with `bad_result`, and an argument too large for the type is rejected |
| `str[N]` | string of at most N UTF-8 bytes; a result that is not UTF-8 text fails with `bad_result` |
| `[T; N]` | array of exactly N items |
| `list[T; N]` | array of at most N items |
| struct | object with every field required, no extra fields |
| `(T1, T2)` | array of exactly that many items, each of its element's type (`prefixItems`) |
| `map[str[K], V; N]` | object of at most N properties, each a value of V |
| `map[K, V; N]` (other keys) | array of at most N `[key, value]` pairs, each key once |
| `set[T; N]` | array of at most N distinct items, in any order |
| enum | `"Variant"` without payload, `{"Variant": [v1, v2]}` with |

Arguments must be strict JSON (RFC 8259: ASCII digits, no leading zeros, escaped control characters,
paired surrogates) and are validated against the parameter types before the call; unknown, missing or
duplicate keys are errors. With no I/O, no heap and bounded loops, a tool written by an agent is safe to
run: it cannot touch the host, cannot exhaust memory, and terminates.

`alang serve file.al` serves the same tools over the Model Context Protocol's stdio transport: JSON-RPC
2.0 messages, one per line, on stdin and stdout, until stdin ends. It answers `initialize` (protocol
revisions 2025-11-25, 2025-06-18, 2025-03-26 and 2024-11-05; a client asking for another gets the
newest), `ping`, `tools/list` and `tools/call`, and ignores notifications. A tool's `inputSchema` and
description are those of `alang tools`; a tool that returns a value also has an `outputSchema`,
`{"result": <schema of the return type>}`, and every tool is annotated read-only, not destructive,
idempotent and closed-world. The text of a call's result is the JSON `alang call` prints; a successful
call of a tool that returns a value also gives `{"result": value}` as `structuredContent`; and a call that
fails, from `bad_arguments` to a trap, is a result with `"isError": true`. Only a name that is not a
`tool fn` is a JSON-RPC error (-32602). The program is checked once, at start: one with errors starts no
server, and its diagnostics, like any warning, go to stderr, which is the only other output.

## 11. Runtime errors (traps)

A trap stops the program (or the current test or tool call) and reports a code `R0001`–`R0013`, a
message and the source location; the interpreter also prints the call stack. The exit code is 1. There
are no exceptions: model recoverable failures as enum return values (see `examples/rpn_calculator.al`).
[ERRORS.md](ERRORS.md) lists every trap.

## 12. The C backend

`alang build file.al -o file.c` produces one self-contained C99 file:

- Values become C structs (arrays are wrapped so they copy by value); `mut` parameters become pointers,
  read-only aggregates `const` pointers.
- No allocation and no stdio: the object file references only `write` and `read` (`_write`, `_read` and
  `_setmode` on Windows, which keep input and output in binary mode), `_Exit` and exact math functions
  (`sqrt`, `floor`, `ceil`, `frexp`, `ldexp`). For bare-metal targets define `AL_WRITE(fd, ptr, len)`,
  and `AL_READ(fd, ptr, len)` (bytes read, 0 at the end) for the input of a `fn main(input: str[N])`,
  which is otherwise empty. That input is read into static storage before main runs.
- A program with imports is still one C file; a trap in an imported file names that file.
- Expressions are lowered to statements so the ALang evaluation order is kept exactly, with every check
  (overflow, bounds, contracts, loop limits) and identical trap messages.
- The runtime prelude formats numbers itself (floats exactly, with big-number arithmetic), so output is
  byte-identical to the interpreter's.

Recommended: `cc -std=c99 -O2 file.c -lm`. The test suite checks gcc and clang at `-O0` to `-O3`.
