# ALang Language Specification (v0.6)

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
  `limit`, `step`, `rev`, `then`, `map`, `set`, `as`, `test`, `traps`, `tool`, `import` and `result` are contextual
  and may be used as names.
- The names of the builtin functions, the standard library included (§9), cannot be redefined (E0108),
  but may be variable names.
- Whitespace between tokens has no meaning. `alang fmt` lays a file out the one way: four spaces per
  block, four more for a line that continues a statement or a bracket opened on an earlier line, one space
  around binary operators and after `,` and `:`, none inside brackets, and at most one blank line in a row.
  It keeps the lines the author broke the code into and every comment, and changes no token.

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
list of capacity N, larger or smaller, and trap (R0004) when the value does not fit (§6). `str[N](x)` of a
number or bool is its text, as `print` writes it: `str[8](42)` is `"42"` (R0004 if it is longer than N). Arrays never
convert to lists, but an array **literal** `[a, b, c]` (or `[v; n]`) can initialize a list with enough
capacity, and `[]` is the empty list.

**Structs** are built with named arguments, each field at most once, in any order:
`Point(x: 1.0, y: 2.0)`; fields are read and written with `p.x`. A field may have a default value, a
constant of its type (`retries: i64 = 3`, E0209 otherwise), which a struct value that leaves the field out
takes; every other field must be given (E0213). `Config()` builds a struct whose fields all have defaults.
`Point(..p, y: 0.0)` takes the fields it does not name from `p`, a variable, a constant or a field of one
(E0213 for anything else, which is read once per field: store it first), of the same struct type; the `..`
may come anywhere among the fields. A field whose type mentions a type parameter has no default (E0218). A
struct cannot contain itself, directly or indirectly (E0403): store linked items in a list and refer to them
by index.

```
struct Point {
    x: f64,
    y: f64 = 0.0,
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
`[3, 1, 3]` a set (`{1, 3}`); only the different keys count against the capacity: more than it holds is E0201 when
they are constants, and otherwise traps (R0004) as the literal is built. `m[k]` reads the value of `k` and traps (R0003) if there is none; `m[k] = v` sets it,
adding `k` if it is new, which traps (R0004) when the map is full; `m[k] += 1`, `m[k].field = x` and an argument
`mut m[k]` need `k` to be there, and trap (R0003) where `m[k]` is evaluated if it is not. `for k in m` goes through
the keys, `for k, v in m` through the keys with their values, `for x in s` through a set, all in order (`rev`
backwards). They print as `{"a": 1, "b": 2}` and `{1, 3}`, compare with `==` by contents (capacities may differ),
and finding a key takes a binary search.

**Enum** variants are always qualified (`Shape.Empty`, `Shape.Circle(2.0)`), and payloads are read only
with `match` (or `if let`). There is no `null`: a value that may be absent is an `Option<T>` (below).

**Type parameters.** A struct or enum can take types as parameters, `struct Pair<A, B> { first: A, second: B }`,
`enum Tree<T> { Leaf(T), Empty }`, and is then a type only with a type for each, `Pair<i64, str[8]>`: an instance,
a struct of its own with those types in place of A and B (E0103 for the wrong number of them). Two instances are
different types, as two structs are. A value takes its type arguments from the type it is stored as, passed as or
compared with, `let p: Pair<i64, bool> = Pair(first: 1, second: true);`, or else from its fields or payload,
`Pair(first: 1, second: true)`; a value that says nothing about one, `Option.None` on its own, is E0216, as is a type
parameter in no field's or payload's type. A type argument has constant capacities (E0217), and a declaration
cannot contain an instance of itself (E0403). Inside a generic function, `Option<T>` takes the function's `T`.

**Option and Result.** Every program has two enums of this kind, built in, which nothing may redeclare (E0108):

```
enum Option<T> { None, Some(T) }        // a value, or none
enum Result<T, E> { Ok(T), Err(E) }     // a value, or the error that kept it from being made
```

Return `Option.Some(i)` or `Option.None` from a search, and `Result.Ok(n)` or `Result.Err(why)` from something that
can fail; a caller takes them apart with `match` or `if let Option.Some(i) = find(xs, x) { }`, with
`let Option.Some(i): Option<i64> = find(xs, x) else { return -1; };` (§4), with `unwrap_or(o, default)`,
`is_some`, `is_none`, `is_ok` and `is_err` (§9.1), or passes the failure on with `?` (§6). Like every enum they
compare with `==`, sort (`None` before `Some`, `Ok` before `Err`), print as `Option.Some(3)`, and are JSON values in
a tool's arguments and result (§10): an `Option` is `null` or the value itself (`3`), and a struct field or tool
parameter of an `Option` type may be left out, as `Option.None`; a `Result` is `{"Ok": [3]}` or `{"Err": ["why"]}`.

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
struct Name<T, U> { field: T, ... }
enum Name { Variant, Variant(Type, ...), ... }
enum Name<T> { Variant, Variant(T), ... }
fn name(param: Type, param: mut Type, param: Type = constant) -> ReturnType requires cond ensures cond { ... }
tool fn name(param: Type) -> ReturnType { ... }
host fn name(param: Type) -> ReturnType requires cond ensures cond;
test "description" { ... }
test "description" traps R0006 { ... }
test "description" with host_fn = fn_name, ... { ... }
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
- **`host fn`** has no body: the program's host answers each call (§10.1). Its arguments go to the host as JSON
  and its result comes back as JSON, so its parameters cannot be `mut`, and its parameters and result have fixed
  capacities and no functions (E0603). It can be called, and passed as a value, like any function.
- **`test "..."`** blocks are run by `alang test`, each from a fresh state. A test passes when its body runs
  to its end; one written `test "..." traps R0006 { ... }` passes only when its body stops with that trap,
  and fails with R0005 when it runs to its end or stops with another trap (whose code and message the
  failure gives). The code is one of R0001 to R0016 (§11); anything else is E0001. In such a test, a call
  whose result is not used and a variable changed but never read are not warned about (W0302, W0305): they
  are there to trap. Its calls do not become a tool's `input_examples` (§10).
  `test "..." with price = fixed_price { ... }` runs `fixed_price` for each call of host fn `price` in that
  test, at any depth: a function with a body of exactly the host fn's type (E0604), which may not call back
  into what calls `price` (E0401 otherwise, as recursion). `traps` comes before `with`. Each test binds its
  own host fns; one it does not bind gets no answer (R0015).

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
- **One namespace, or a name of its own.** The constants, structs, enums and functions of a file imported
  plainly are used by their plain names, as if declared in the importing file, and its names and the
  importer's are unique together (E0104 names both files). `import "lib/geo.al" as geo;` instead keeps the
  file's declarations apart, in a scope of their own: the importer reaches them as `geo.describe(p)`,
  `geo.SCALE`, `geo.Point(x: 1, y: 2)`, `geo.Kind.Origin`, `geo.Shape.Circle(2.0)`, the type `geo.Point`, the
  pattern `geo.Kind.Axis(d) =>` and the function value `geo.is_far`, and only so, while their names may be
  the same as the importer's or another library's. Inside geo.al, and in the files it imports plainly, the
  plain names are geo's own. A file shared by files of different scopes is imported with `as` in each, under
  any name; importing it with `as` in one place and plainly in another is E0702, as is giving two imports of
  one file the same name. There are no private declarations.
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

### 3.2 Packages

A package is a directory of `.al` files that a program uses as a unit: it is declared once, with its
directory and a hash of its files, and imported by name: `import "@text/fmt.al";`, or `import "@text/fmt.al"
as text;`. After the `@` come the package's name and the path of a file in it; the rules of §3.1 apply to the
import as to any other.

The packages are declared in `alang.json`, the **manifest**, a JSON object:

```json
{
  "name": "report",
  "version": "1.0.0",
  "dependencies": {
    "text": {"path": "vendor/text", "sha256": "0096f11df990d38344207513bc499a318545fbf9740369bd9ea09a275351f5f1"}
  }
}
```

- `"name"` and `"version"` describe the directory's own package, when it is one; both are optional, and
  `alang deps` shows the version of each dependency. A package name is lowercase letters, digits and `_`,
  starting with a letter.
- Each dependency has a `"path"`: its directory, relative to `alang.json`, with `/` (E0705 if it is not, or
  does not exist). **The toolchain never downloads anything**: a package is vendored there, by copying it or
  as a git submodule, and is part of the program's tree.
- The manifest that counts for an import is the **nearest**: the one in the importing file's directory, or
  the closest directory above it. For a file of a package it is that package's own `alang.json`, at the top of
  its directory, never one above it, so a package means the same wherever it is vendored. A name no manifest
  declares is E0704.
- **Each package is pinned** by its `"sha256"`: the SHA-256 of each `.al` file and `alang.json` in its
  directory and below, in the order of their paths relative to it (with `/`), each as the path, a NUL byte,
  its length in decimal, a NUL byte and its bytes, with CRLF read as LF. A package that is not pinned, or whose
  files no longer match their pin, is E0706, with its hash in the hint. `alang deps prog.al --lock` writes the
  pins.
- **One copy of each package.** A package may vendor its own dependencies. Copies with the same name and the
  same hash, wherever they are, are one package, read once; two copies with different hashes in one program
  are E0706.
- **A package is closed.** Its files import each other by relative paths that stay inside its directory, and
  other packages with `@` (E0702 otherwise), so its hash covers all the code it can reach. A package file
  shared by files of different scopes is imported with `as` in each, as in §3.1.

`alang deps prog.al` lists the dependencies of the program's manifest, with each one's directory, version
and state: `ok`, `unpinned`, `changed` or `missing` (`--json`: one object with `"ok"`, `"manifest"` and
`"packages"`); it exits with 1 unless each is `ok`. `--lock` pins each one that is unpinned or changed to its
files as they are now, rewriting `alang.json`. Pin a package's own dependencies, in its manifest, before the
package itself: its manifest is part of its hash.

## 4. Statements

| Statement | Form |
|-----------|------|
| binding | `let name: Type = expr;` (immutable), `var name: Type = expr;` (mutable), `let (a, b): (T1, T2) = tuple;`, `let Point(x: px, y: _): Point = p;` (a pattern that always matches), `let Option.Some(v): Option<i64> = o else { return 0; };` (one that can fail, with a block that leaves) |
| assignment | `place = expr;` and `+= -= *= /= %=` |
| condition | `if c { } else if c { } else { }`; any condition may be `let Pattern = expr` (`if let Option.Some(v) = m { }`) |
| loops | `while c limit n { }`, `while let Pattern = expr limit n { }`, `for i in a..b { }` (half-open), `for i in a..=b { }` (inclusive), either with `step k`, and `rev` before either for the same values last first; `for x in xs { }` (array, list or str: `u8` bytes), `for i, x in xs { }` (with each index), `for x in rev xs { }`, `for (k, v) in pairs { }` (each element taken apart by a pattern); `for p in split(s, sep) { }`, `for w in words(s) { }`, `for c in chars(s) { }` |
| labels | `outer: for ... { }` or `outer: while ... { }`, then `break outer;` or `continue outer;` from a loop inside it |
| match | `match e { Pattern => { } ... }` (and as a value: `match e { Pattern => value, ... }`, §6) |
| jumps | `break;` `continue;` `return;` `return expr;` |
| assertion | `assert cond;` `assert cond, "message";` `assert_eq(a, b);` (`assert a == b;`) |
| call | `f(args);` |

- Every binding has an explicit type and an initializer.
- A `place` is a variable followed by field accesses and indexes (`inv.items[i].quantity`); its root must
  be a `var` or a `mut` parameter (E0205).
- Conditions must be `bool`; there is no truthiness (E0211).
- `while c limit n`: `n` (any integer type) is evaluated once, before the first test of `c`. The program
  traps (R0008) when the body is about to run for the `n + 1`-th time. The limit is a promise about the
  algorithm, e.g. `limit 64` for a binary search over a 64-bit range.
- `while let P = e limit n`: before each iteration `e` is evaluated and matched against `P`; the loop ends
  when it does not match, and otherwise the body runs with the names `P` binds, as an arm of a `match` would.
  The test that ends the loop is not an iteration: `while let Option.Some(x) = next(xs) limit 4` over four
  values runs four times. The pattern need not cover every value. `e` may be a call with `mut` arguments, which
  then runs before each iteration: `while let Option.Some(x) = next(mut queue) limit 64 { }`.
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
- `for P in xs`, with a pattern `P` in place of the name, takes each element apart as `let P: T = x;` would:
  `for (name, n) in pairs { }`, `for i, (k, v) in pairs { }`, `for k, (lo, hi) in m { }` (the values of a map),
  `for Point(x: px, y: py) in pts { }`. The pattern must match every element (E0306): to skip some, take them
  with a name and use `let ... else { continue; };` in the body.
- `break label;` and `continue label;` leave or continue the loop labelled `label:` around them, from any
  depth; `break;` and `continue;` the innermost loop. Labels of loops inside one another differ (E0105).
- `for p in split(s, sep)` and `for w in words(s)` go through the pieces of `s` one at a time (§9.4),
  without building a list: each piece is a `str` of the capacity of `s`, which no piece can outgrow, so the
  loop never traps on the size or number of the pieces. `s` and `sep` are evaluated once, before the first
  piece, like a collection. Narrow a piece with `str[N](p)` after checking `len(p)`.
- Loop variables, match bindings and `let` bindings are immutable; the names of a `var` pattern are mutable.
- Only calls may be expression statements (E0305); ignoring a non-void result is warning W0302.
- A program without errors is also checked for work that is lost: a local or match binding that is never
  used (W0304; write `_` for a binding), a variable that is changed but never read (W0305), often a copy
  such as `var it: Item = items[0]; it.qty += 1;` that leaves `items[0]` as it was, and a function of the
  checked file that nothing calls (W0306). Names that start with `_` are left alone.
- A function with a return type must return on every path; loops never count as returning (E0301).

**Match** works on enums, integers, `bool`, `str`, tuples and structs. Each arm body is a block; the first arm
whose pattern matches runs. Patterns nest:

| Pattern | Matches |
|---------|---------|
| `_` | anything |
| `name` | anything, and binds a copy of it to a new variable `name` |
| `3`, `-1`, `'x'`, `"text"`, `true` | that value |
| `1..=9`, `'a'..='z'`, `0..10` | an integer from the first bound to the second (`..` leaves the second out) |
| `Enum.Variant`, `Enum.Variant(p1, p2)` | that variant, with each payload value matching its pattern |
| `(p1, p2)` | a tuple whose elements match |
| `Point(x: p1, y: p2)` | a struct whose named fields match; the fields left out match anything |
| `p1 \| p2` | any of the alternatives, which bind the same names (of the same types) |

A match must be exhaustive (E0306): together its arms take every value, which an enum, `bool`, tuple or struct can
do arm by arm, while integers and strs always need a final `_` or name. An arm that takes no value the arms above it
leave, such as `5` below `1..=10`, is unreachable (E0307), as is any arm below a `_`. The names of an or-pattern take
their values from the first alternative that matches.

`let Pattern: Type = value;` takes a value apart with a pattern that matches every value of the type (E0306
otherwise): a tuple or struct of names, `_`s and such patterns. With an else block, the pattern may fail:
`let Option.Some(n): Option<i64> = parse_i64(s) else { return 0; };` binds `n` when the value matches and
otherwise runs the block, which must not reach its end (E0309): it returns, breaks, continues or panics, since
the names of the pattern have no value after it. Its names are not in scope inside the block, and a pattern that
always matches leaves the block nothing to do (E0307). Any pattern of a match arm can be used. `if let Pattern = value { A } else { B }` runs A,
with the pattern's names bound, when the value matches, and B otherwise; it is `match value { Pattern => { A } _ =>
{ B } }`, so a pattern that always matches is E0307, and `else if` may chain more conditions of either kind.

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
  `swap(mut xs[i], mut xs[j])`, since `i` and `j` may be equal; write `swap(mut xs, i, j)`. A read-only number or bool
  is a copy made before the call, so `remove(mut xs, len(xs) - 1)` is fine.
- **Mutating calls are statements (E0215):** a call with a `mut` argument is a statement of its own or the
  whole right-hand side of `let`/`var`/assignment/`return` or of `while let P =`, so every mutation is at a
  visible point.
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
`largest_of(x, 1)` with `x: u8` makes the `1` a `u8`, and literals before `[]` and `Option.None`, which give none,
so that `first_or([], 5)` makes `T` an `i64`), which the others must then match exactly. Like a `_`, each
call with new types runs an instance made for them, checked again with the types filled in (`alang check
--memory` lists them). Structs and enums take type parameters too (§2).

**Function parameters.** A parameter of a function type, `keep: fn(T) -> bool`, takes a function by its name,
`first_where(xs, is_even, 0)`, and the body calls it, `keep(x)`. The function's parameter and result types must be the
parameter's exactly (type parameters aside, which they can fix), it cannot have `mut` parameters, and it cannot be
a builtin (wrap one in a function of your own) or itself take a `_`, type parameter or function. A parameter of a
function type can be passed on to another function, but not stored, returned or put in a variable: there are no
closures. Each call with a new function runs an instance that calls that function directly, so the call graph
stays known, and passing a function to something that calls it back is recursion (E0401).

```
fn first_where<T>(xs: list[T; _], keep: fn(T) -> bool, otherwise: T) -> T {
    for x in xs {
        if keep(x) {
            return x;
        }
    }
    return otherwise;
}
```

**Default values.** `fn area(w: f64, h: f64 = 1.0)` gives `h` a default: a constant expression of the parameter's
type, evaluated once while checking, like a `const`, which a call that leaves the argument out passes in its place:
`area(2.0)` is `area(2.0, 1.0)`. A call leaves out arguments from the end only, so every parameter after one with a
default needs one too, and a call passes from the required ones up to all of them (E0204). A default cannot use the
other parameters (E0101) or call functions (E0209), and only a read-only parameter of a fixed type has one: not a
`mut` parameter, one whose type has a `_` or a type parameter, one of a function type, or the input of `main`
(E0218). A tool's parameter with a default is optional in its JSON (§10).

**Contracts.** `requires` clauses are checked on entry, in order; a failure is trap R0006, reported **at
the call site** (the caller broke the contract). `ensures` clauses are checked on every return, with
`result` as the return value and `mut` parameters at their final values; a failure is trap R0007,
reported at the clause, with the returned value.

```
fn int_sqrt(x: i64) -> i64
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
| postfix | `e?` `e.f` `e[i]` | bind tightest |
| unary | `-x` `~x` | |
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

**Constants of the number types** are written after the type with a dot, and have that type: `i8.MIN` to
`u64.MAX` for every integer type (`MIN` and `MAX`), and for `f32` and `f64` `PI`, `TAU` (2π), `E`,
`INFINITY`, `NAN`, `EPSILON` (the gap between 1.0 and the next value), `MAX` (the largest finite value),
`MIN` (`-MAX`) and `MIN_POSITIVE` (the smallest normal value). `var best: i64 = i64.MAX;` starts a search
for a smallest value; `i64::MAX` is E0001 with a fix, `Math.PI` an unknown name with a hint.

| From → To | Behavior |
|-----------|----------|
| integer → integer | value preserved; out of range traps (R0009) |
| integer → float | rounded to nearest (ties to even) |
| float → integer | truncated toward zero; NaN or out of range traps (R0009) |
| f64 → f32 | rounded to nearest; may become `inf` |
| f32 → f64 | exact |
| bool → integer | `true` → 1, `false` → 0 |
| str → integer | decimal text: an optional `+` or `-`, then digits; other text or out of range traps (R0009) |
| str → f64 | decimal text: an optional `+` or `-`, then digits with at most one `.` among them, then optionally an exponent: `e` or `E`, an optional sign and digits (`2.5`, `-0.5`, `3.`, `.25`, `1e5`, `2.5E-3`); the nearest f64. It converts when the digits from the first to the last that is not 0 make a number of at most 2^53, between about 1e-22 and 1e37 in size, so that one correctly rounded operation gives that f64 on every backend: 15 significant digits always do. Other text traps (R0009); `is_float(s)` says first. For an f32, `f32(f64(s))`. |
| anything → bool | not allowed (E0214); compare instead |
| `str[M]` → `str[N]` | the same bytes; more than N bytes traps (R0004) |
| `list[T; M]` → `list[T; N]` | the same elements, of the same type T; more than N traps (R0004) |

**Slices.** `xs[a..b]` is the elements (or bytes) `a..b` of a str, list or array, the value `slice(xs, a, b)`
gives: a str or list of the capacity of `xs`, or a list for an array. A left-out end is 0 or `len(xs)`: `s[i + 1..]`,
`xs[..3]`, `xs[..]`. The bounds are evaluated after `xs`, and the slice traps (R0003) unless
`0 <= a <= b <= len(xs)`. A slice is a value, not a place: `xs[1..3] = ...` is an error. Where it is only read
(a `for` loop over it whose body leaves its variable alone, `len`, `contains`, `index_of`, `count`, the text
functions, a comparison or `print` of a str, a conversion `i64(s[2..5])`, a `match` on it), the C backend reads it
where it is, through a pointer and a length, and the memory bound counts no copy of it.

**The `?` operator.** `e?`, after a value `e` of type `Option<T>` or `Result<T, E>`, is the `T` in it when it is
`Option.Some` or `Result.Ok`. When it is `Option.None` or `Result.Err(why)`, the function returns that at once, as a
value of its own return type: `let n: i64 = parse_i64(s)?;` in a function that returns an `Option`, or
`total += digit(c)?;` in one that returns a `Result` whose error type can hold `E` (`str[8]` widens to `str[32]`;
E0310 otherwise, and for an `Option` in a function that returns a `Result` or the other way round). The return goes
through the function's `ensures`, as a `return` does. `?` may be anywhere in an expression and is evaluated where it
is: `flag and parse_i64(s)? > 0` returns only when `flag` holds. `f(x)?;` on its own is a statement, for the
failure only. It is E0310 in a test, `fn main`, a `requires` or `ensures` clause or a default value, which have no
`Option` or `Result` to return. There is no `c ? a : b`: write `if c then a else b`.

**Match expressions.** `match e { P => a, Q if c => b, _ => d }` is the value of the first arm whose pattern (and
guard) matches; only that arm's value is evaluated. Its arms are values, separated by commas, and checked like a match
statement's (E0306, E0307); their values have one type, as an if expression's branches do. Like an if expression it is
a whole expression, never an operand, and a call with `mut` arguments cannot be an arm's value (E0215).

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

- **No sources of nondeterminism:** no clock, random numbers (the generator of `random_u64` and its kin is
  seeded by the program, §9.2), environment, file or network I/O, threads,
  hash-ordered collections, addresses or uninitialized memory. The only effect is `print`; the only
  inputs are a tool call's arguments, the standard input that `alang run` passes to a
  `fn main(input: str[N])`, read whole before main starts, and the replies to `host fn` calls (§10.1),
  which every run records (`host_calls`) so that `--replay` gives them again. Imports are read when the
  program is checked, relative to the importing file, so they add no input either. A program without host
  fns has no inputs but the first two.
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
- **A deterministic step budget:** the interpreter counts steps. A statement or a loop iteration is one,
  and work on data adds steps in proportion to it: a byte of a str, or a slot of a list, array, map or set
  that an operation makes, moves or clears, is 1/128 of a step; an element, field or entry it handles one
  at a time is 1/8, and a comparison of `sort` a little under 1/2; writing an integer as text, and each
  float function (`sqrt`, `sin`, `pow` and the like), is one step, and writing a float about 22, more for
  very large or very small ones, since its exact decimal value is worked out first. So a step takes about as long as a simple statement
  whatever the program does, and `--max-steps` (default 100 000 000, a few seconds of work) bounds the
  time a run takes, not only the statements it runs. The count depends only on the program and its input:
  R0011 stops a runaway program after exactly the same number of steps every time. Only the interpreter
  counts steps; the C backend has no budget.
- **Deterministic tooling:** diagnostics are sorted by file and position, JSON has a fixed key order, generated C
  is byte-identical for identical input, and nothing depends on the host locale or line endings.

The test suite runs every program repeatedly and under several locales, and compares the C backend byte
for byte with the interpreter under several compilers and optimization levels.

## 9. Builtins and the standard library

Builtin functions come with the language. They are polymorphic where a user function cannot be (a user
function takes any capacity, §5, but not any element type): `len` takes any array, list or str, `sort`
any list. The core builtins print and change
lists and strings; the rest are the standard library: math, characters, text, dates and collections. Each
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
| `pop_front(mut xs) -> T` | Removes and returns the first element, moving the rest down: a queue with `push` at the back. Traps if empty (R0012). |
| `clear(mut xs)` | Removes all elements. |
| `append(mut s, values...)` | Appends the text of each value to a str. Traps if it does not fit (R0004). |
| `panic(message)` | Stops with trap R0010. |
| `assert_eq(a, b);` | A statement: `assert a == b;`, for two values `==` compares. When they differ it traps (R0005) with both: `assertion failed: assert_eq(total, 10) (left: 9, right: 10)`. |
| `unwrap_or(o, d) -> T` | The value in an `Option<T>` or a `Result<T, E>` when it is `Option.Some` or `Result.Ok`, otherwise `d`, a `T`: `unwrap_or(parse_i64(s), 0)`. |
| `is_some(o)`, `is_none(o)`, `is_ok(r)`, `is_err(r)` -> `bool` | Which variant an `Option` or a `Result` is. |

### 9.2 Math

| Function | Description |
|----------|-------------|
| `abs(x)`, `min(a, b)`, `max(a, b)` | Same-type numbers. `abs` of the minimum integer traps (R0001). |
| `min(xs)`, `max(xs)` | The smallest or largest element of a non-empty array or list of numbers, compared as `min(a, b)` compares (a NaN is kept only if it is first). Traps (R0013) if it is empty. |
| `sum(xs) -> T` | The elements of an array or list of numbers added left to right, as `+` adds them, in their type; 0 if it is empty. An integer total that does not fit traps (R0001), as `+` does, so sum a `list[u8; N]` into a wider type with a loop. |
| `product(xs) -> T` | The elements multiplied left to right, as `*` multiplies them, in their type; 1 if it is empty. An integer product that does not fit traps (R0001). |
| `clamp(x, lo, hi) -> T` | `lo` if `x < lo`, `hi` if `x > hi`, else `x`; same-type numbers. Traps (R0013) unless `lo <= hi`, so a NaN bound traps. |
| `pow(x, n) -> T` | `x` (any integer type) to the power `n` (any integer type); `pow(0, 0)` is 1. Traps if `n < 0` (R0013) or the result does not fit (R0001). For an `f32`/`f64` `x`, `n` is a float of the same type (`pow(2.0, 0.5)`, `pow(x, 3.0)`) and nothing traps: the special cases are C99's (`pow(-8.0, 0.5)` is NaN, `pow(0.0, -1.0)` is `inf`), an integer `n` gives the correctly rounded power (`pow(2.0, 10.0)` is `1024.0`), and `pow(x, 0.5)` is `sqrt(x)`. |
| `isqrt(n) -> T` | The integer square root of an integer `n >= 0`, of any type: the largest `r` with `r * r <= n`, exact for every value (`isqrt(17)` is 4). Traps (R0013) if `n` is negative. |
| `gcd(a, b) -> T` | Greatest common divisor of same-type integers, never negative; `gcd(0, 0)` is 0. `gcd(x, 0)` for the minimum `x` does not fit and traps (R0001). |
| `lcm(a, b) -> T` | Least common multiple, never negative; 0 if either is 0. Traps if it does not fit (R0001). |
| `wrapping_add(a, b)`, `wrapping_sub(a, b)`, `wrapping_mul(a, b)` | Same-type integers; the exact result wrapped around to the type (modulo 2^bits), so they never trap: `wrapping_add(u8(250), 10)` is `4`, `wrapping_mul(h, 16777619)` is the multiply of 32-bit FNV. |
| `saturating_add(a, b)`, `saturating_sub(a, b)`, `saturating_mul(a, b)` | Same-type integers; the exact result, or the type's largest or smallest value when it does not fit: `saturating_sub(u8(3), 5)` is `0`. Never trap. |
| `checked_add(a, b)`, `checked_sub(a, b)`, `checked_mul(a, b)` -> `Option<T>` | Same-type integers; the exact result as `Option.Some`, or `Option.None` when it does not fit: `checked_sub(u8(3), 5)` is `Option.None`. Never trap. |
| `rem_euclid(a, m) -> T` | Same-type integers; the remainder of `a / m` that is never negative, `0 <= r < abs(m)`: `rem_euclid(-1, 5)` is `4`, where `-1 % 5` is `-1`. Wraps an index around: `xs[rem_euclid(i + k, n)]`. Traps (R0002) if `m` is 0. |
| `div_euclid(a, m) -> T` | The quotient that goes with it, `a == div_euclid(a, m) * m + rem_euclid(a, m)`: `div_euclid(-7, 2)` is `-4`, where `-7 / 2` is `-3`. Traps (R0002) if `m` is 0, and (R0001) where `/` overflows. |
| `mul_mod(a, b, m) -> T` | Same-type integers; `(a * b) mod m` computed exactly however large `a * b` is, from 0 to `m - 1` (`a` and `b` reduced as `rem_euclid` reduces them). Traps (R0013) unless `m > 0`. |
| `pow_mod(b, e, m) -> T` | Same-type integers; `b` to the power `e`, mod `m`, exactly however large the power is (by squaring with `mul_mod`), from 0 to `m - 1`, `b` reduced as `rem_euclid` reduces it: `pow_mod(2, 10, 1000)` is 24, `pow_mod(x, 0, m)` is `1 % m`. Traps (R0013) unless `e >= 0` (first) and `m > 0`. |
| `abs_diff(a, b) -> T` | Same-type numbers; the distance `abs(a - b)`, without the subtraction that could trap first: `abs_diff(u8(3), 5)` is 2, where `u8(3) - 5` traps. Never traps for unsigned integers or floats; for signed ones, traps (R0001) when the distance does not fit, as `abs_diff(i64.MIN, 0)` does. |
| `sign(x) -> T` | -1, 0 or 1 as the number `x` is negative, zero or positive, in its type (0 or 1 when unsigned); for a float -1.0 or 1.0, and `x` itself when it is 0.0, -0.0 or NaN. |
| `count_ones(x) -> i64` | The number of 1 bits of an integer over the bits of its type (two's complement for a signed one): `count_ones(i8(-1))` is 8. |
| `leading_zeros(x) -> i64`, `trailing_zeros(x) -> i64` | The 0 bits above the highest 1 bit, or below the lowest, over the bits of the type: `leading_zeros(u8(1))` is 7, `trailing_zeros(u8(8))` is 3; the number of bits for 0. |
| `rotl(x, n) -> T`, `rotr(x, n) -> T` | The bits of the integer `x` rotated left or right by `n` (any integer), the bits that leave at one end coming back at the other: `rotl(u8(0x81), 1)` is `0x03`. `n` is taken as `rem_euclid(n, bits)`, so a negative `n` rotates the other way. Never trap. |
| `mean(xs) -> f64` | The mean of a non-empty array or list of numbers of any type: the elements converted with `f64(x)` and added first to last, then divided by their number. Traps (R0013) if it is empty. |
| `median(xs) -> f64` | The middle element of a non-empty array or list of numbers, in the order of `sort`, as an `f64`; for an even number of them `(a + b) / 2.0` of the two in the middle. `xs` is left as it is. Traps (R0013) if it is empty. |
| `variance(xs) -> f64`, `stddev(xs) -> f64` | The population variance of a non-empty array or list of numbers, the mean of `(f64(x) - mean(xs))^2` with the terms added first to last (divided by n, not n - 1), and its square root. Traps (R0013) if it is empty. |
| `sqrt(x)`, `floor(x)`, `ceil(x)` | On `f32`/`f64`, correctly rounded. |
| `round(x)`, `trunc(x)` | On `f32`/`f64`, exact: `round` halves away from zero (`round(2.5)` is `3.0`, `round(-0.5)` is `-1.0`); `trunc` drops the fraction (`trunc(-2.7)` is `-2.0`). |
| `exp(x)`, `log(x)`, `log2(x)`, `log10(x)` | On `f32`/`f64`: e^x and the logarithms; `log` of 0 is `-inf` and of a negative number NaN. `log2` of a power of two and `log10` of a power of ten are exact. |
| `sin(x)`, `cos(x)`, `tan(x)` | On `f32`/`f64`, in radians, for every finite `x` (a large argument is reduced exactly). |
| `asin(x)`, `acos(x)`, `atan(x)`, `atan2(y, x)` | On `f32`/`f64`, in radians: `asin` and `atan` from -pi/2 to pi/2, `acos` from 0 to pi (NaN outside -1..1), `atan2` the angle of the point (x, y) from -pi to pi. |
| `is_nan(x)`, `is_finite(x)` | On `f32`/`f64`. |

**Pseudo-random numbers** come from a generator whose state is a `u64` (or `i64`) variable of the program,
passed as `mut` and advanced by each call: SplitMix64, so a seed gives the same numbers on every run and
backend, and there is no hidden state. `var rng: u64 = 42;` starts one.

| Function | Description |
|----------|-------------|
| `random_u64(mut rng) -> u64` | The next number of the generator. |
| `random_int(mut rng, lo, hi) -> i64` | A number from `lo` to `hi` (two `i64`), both included, every value equally likely: a draw below 2^64 mod the size of the range is drawn again. Traps (R0013) unless `lo <= hi`. |
| `random_f64(mut rng) -> f64` | A number from 0.0 (included) to 1.0 (excluded): the top 53 bits of the next draw times 2^-53. |
| `shuffle(mut xs, mut rng)` | Puts the elements of a list or array (bytes of a str) in a random order, every order equally likely: Fisher-Yates from the last element down, element `i` exchanged with `random_int(mut rng, 0, i)`. |

Each is a call with `mut` arguments, so it is its own statement or the whole right side of a `let` (rule
11): `let die: i64 = random_int(mut rng, 1, 6);`.

The elementary functions are within one unit in the last place of the exact result (`tan` within two), and the
same bits everywhere: ALang computes them itself (`exp`, `log`, `log10`, `sin`, `cos`, `asin`, `acos`, `atan` and
`atan2` as fdlibm does, see THIRD-PARTY-NOTICES), and an `f32` result is the `f64` one rounded once.

A literal argument takes the type of the other arguments or, for `min`, `max`, `clamp`, `pow`, `gcd`,
`lcm`, `rem_euclid`, `div_euclid`, `mul_mod`, `pow_mod`, `abs_diff`, `sign`, `rotl`, `rotr` and the wrapping and saturating functions, of the variable the result is
stored in: `let b: u8 = pow(2, 7);`.

### 9.3 Characters

On one byte, a `u8` such as `s[i]`, a loop variable of `for c in s`, or a character literal; ASCII only,
so every other byte is not a letter, digit or space and is left as it is.

| Function | Description |
|----------|-------------|
| `is_digit(c)`, `is_alpha(c)`, `is_alnum(c)` | `0-9`; `a-z` and `A-Z`; either. |
| `is_upper(c)`, `is_lower(c)` | `A-Z`; `a-z`. |
| `is_space(c)` | Space, `\t`, `\n`, `\r`, `\x0b` or `\x0c`. |
| `is_hex_digit(c)` | `0-9`, `a-f` or `A-F`. |
| `is_punct(c)` | A printable ASCII character that is no letter, digit or space: ``!"#$%&'()*+,-./:;<=>?@[\]^_`{\|}~``. |
| `is_ascii(x)` | A `u8` below 128, or a str whose every byte is. |
| `to_upper(x)`, `to_lower(x)` | A `u8`, or every byte of a `str[N]`, with its case changed; the same type back. |

### 9.4 Text

What `contains` and `index_of` look for in a str, a prefix or suffix, a separator, and what `replace`
replaces or puts in, is a **pattern**: a str, or one `u8` byte (`split(line, ',')` and
`split(line, ", ")` both work).

| Function | Description |
|----------|-------------|
| `contains(xs, x) -> bool` | In a str, whether the pattern `x` occurs (the empty str always does); in an array or list, whether an element `== x`. |
| `index_of(xs, x) -> i64` | Where the first occurrence starts, as for `contains`, or -1. `index_of(xs, x, from)` looks from position `from` on (of any integer type) and still counts from the start: `index_of("abab", "b", 2)` is 3. Traps (R0003) unless `0 <= from <= len(xs)`. |
| `last_index_of(xs, x) -> i64` | Where the last occurrence starts, or -1; `len(xs)` for the empty str. |
| `count(xs, x) -> i64` | In a str, the non-overlapping occurrences of the pattern `x`, left to right (`count("aaaa", "aa")` is 2); traps (R0013) if `x` is an empty str. In an array or list, the elements `== x`. |
| `starts_with(s, p) -> bool`, `ends_with(s, p) -> bool` | Whether the str `s` begins or ends with the pattern `p`. |
| `slice(xs, from, to) -> T` | The elements `from..to` (half-open) of a str or list, of the same type, or of an array, as a `list[T; N]`. Traps (R0003) unless `0 <= from <= to <= len(xs)`. `xs[from..to]` is the same, and either end may be left out: `s[1..]`, `xs[..n]`, `xs[..]` (§6). |
| `trim(s) -> str[N]` | `s` without the ASCII whitespace at its start and end. |
| `trim_start(s) -> str[N]`, `trim_end(s) -> str[N]` | `s` without the ASCII whitespace at its start (end) only. |
| `strip_prefix(s, p) -> str[N]`, `strip_suffix(s, p) -> str[N]` | `s` without the pattern `p` at its start (end), or `s` unchanged when it does not start (end) with it, as a str of the capacity of `s`: `strip_prefix("v1.2", 'v')` is `"1.2"`. |
| `split_once(s, sep) -> Option<(str[N], str[N])>` | The text before and after the first occurrence of the pattern `sep`, each a str of the capacity N of `s` (or of the wider strs of the `Option` it is stored as), or `Option.None` when there is none: `split_once("k=v=w", '=')` is `Option.Some(("k", "v=w"))`. Traps if `sep` is empty (R0013). |
| `split(s, sep) -> list[str[K]; M]` | The pieces of `s` between occurrences of the pattern `sep`, left to right, empty pieces included: `split("a,,b", ",")` is `["a", "", "b"]`, `split("", ",")` is `[""]`. Traps if `sep` is empty (R0013). |
| `words(s) -> list[str[K]; M]` | The pieces of `s` between runs of ASCII whitespace; never empty pieces. |
| `join(parts, sep) -> str[N]` | The strs of an array or list, with the pattern `sep` between them. |
| `replace(s, from, to) -> str[N]` | `s` with every occurrence of the pattern `from`, left to right and not overlapping, replaced by the pattern `to`. Traps if `from` is empty (R0013). |
| `try_split(mut parts, s, sep) -> bool` | `split(s, sep)` into the list `parts`, replacing what it held: true if every piece fits, otherwise false with `parts` left empty, never a trap. |
| `try_words(mut parts, s) -> bool` | `words(s)` into the list `parts`, as `try_split` does. |
| `split_csv(s, sep = ',') -> list[str[K]; M]` | The fields of the CSV record `s` between the `u8` byte `sep`, as RFC 4180 writes them: a field in double quotes may hold `sep`, and `""` inside it stands for one `"`. `split_csv("1,\"Smith, Jo\",\"say \"\"hi\"\"\"")` is `["1", "Smith, Jo", "say \"hi\""]`. Spaces are kept, a `"` inside a field without quotes is an ordinary byte, and one `\r` at the end of `s` is left out (a line of a CRLF file). Each field is checked in turn: that the list has room (R0004), that its quotes are well formed (R0013: no closing quote, or text between the closing quote and `sep`), that it fits a `str[K]` (R0004). |
| `try_split_csv(mut fields, s, sep = ',') -> bool` | `split_csv(s, sep)` into the list `fields`: false, with `fields` left empty, where `split_csv` would trap. |
| `re_match(s, pattern) -> bool` | Whether the regular expression `pattern` matches all of `s`. A byte matches itself; `.` any byte but a newline; `[abc]`, `[a-z]`, `[^...]` a class; `\d \w \s` (and `\D \W \S`); `^` and `$` the start and end of `s`; `( )` a group; `\|` alternatives; `* + ?` and `{m}`, `{m,}`, `{m,n}` (up to 100), greedy; `\` before a symbol the symbol. Write patterns as raw strings, `r"\d+"`. Time is linear in the length of `s`. A pattern written out that is not valid is E0202; one computed traps (R0013), as does one over 256 bytes. |
| `re_find(s, pattern) -> Option<(i64, i64)>` | Where `pattern` first matches: `Option.Some((start, end))`, `s[start..end]` the match, leftmost and then as Perl chooses (left alternative first, repetitions as long as they go), or `Option.None`. |
| `re_replace(s, pattern, to) -> str[N]` | `s` with every match of `pattern` replaced by the pattern `to`, put in as it is: `re_replace("a1b22", r"\d+", "#")` is `"a#b#"`. Matches are found left to right, each from where the last ended; an empty match is replaced too, except where the last match ended empty, as Python's `re.sub` does: `re_replace("abxd", "x*", "-")` is `"-a-b--d-"`. The whole length is checked once it is known (R0004). |
| `re_split(s, pattern) -> list[str[K]; M]` | The pieces of `s` between the matches of `pattern`, found as `re_replace` finds them, empty pieces included, as Python's `re.split`: `re_split("a, b;c", r"[,;] *")` is `["a", "b", "c"]`. Checked as `split` is. |
| `to_json(v) -> str[N]` | The JSON text of any value whose type has no function, type parameter or `_` capacity, on one line with a space after each `,` and `:`, in the forms of tool results and `from_json` (§10): `to_json(Point(x: 1, y: 2))` is `{"x": 1, "y": 2}`. Strs are strings (`"` `\` and control bytes escaped, other bytes as they are); a float has the fewest significant digits that read back as the same value, the nearest of those, and is plain from 1e-7 to below 1e21 with `.0` when whole (`0.1`, `100.0`, `-0.0`) and otherwise has an exponent (`1e+21`, `5e-324`). Traps (R0013) on NaN, an infinity or a str that is not UTF-8. Makes text (§9.6). |
| `from_json(s) -> Option<T>` | The value of type `T` that the JSON text `s` stands for, `T` that of the `Option<T>` it is stored in: `let cfg: Option<Config> = from_json(text);`. Read as a tool's arguments are (§10); `Option.None` for any other text. `from_json(to_json(v))` is `Option.Some(v)`. |
| `variant_name(e) -> str[N]` | The name of the variant of the enum value `e`, without the enum or its values: `variant_name(Shape.Circle(2.0))` is `Circle`. Makes text (§9.6). |
| `parse_variant(s) -> Option<E>` | The variant of the enum `E` named exactly by `s`, `E` that of the `Option<E>` it is stored in, or `Option.None`: `let c: Option<Color> = parse_variant("Red");`. `E`'s variants must carry no values. |
| `is_int(s) -> bool` | Whether `i64(s)` would succeed: decimal text that fits an `i64`. |
| `is_float(s) -> bool` | Whether `f64(s)` would succeed: decimal text that converts exactly (§6). |
| `parse_i64(s) -> Option<i64>` | `Option.Some(i64(s))` when `is_int(s)`, else `Option.None`: never traps. Strict, as `i64(s)` is: `trim` first. |
| `parse_i64(s, base) -> Option<i64>` | The same in a base from 2 to 36 (any integer type; R0013 otherwise): an optional `+` or `-`, then at least one digit, `0`-`9` then `a`-`z` or `A`-`Z`, with no prefix such as `0x`. `parse_i64("ff", 16)` is `Option.Some(255)`, `parse_i64("-101", 2)` is `Option.Some(-5)`. |
| `hash(s) -> u64` | The 64-bit FNV-1a hash of the bytes of a str, or of an array or list of `u8`: the same number on every run and machine; `hash("")` is 14695981039346656037. Not for secrets. |
| `sha256(data) -> str[64]` | The SHA-256 digest (FIPS 180-4) of the bytes of a str, or of an array or list of `u8`, as 64 lowercase hexadecimal digits. |
| `crc32(data) -> u32` | The CRC-32 of the bytes of a str, or of an array or list of `u8`, as zlib, gzip and PNG compute it: `crc32("123456789")` is 3421780262. |
| `base64_encode(data)`, `hex_encode(data)` | The Base64 text (RFC 4648: `A-Z`, `a-z`, `0-9`, `+`, `/`, padded with `=`) or the two lowercase hexadecimal digits of each byte of a str, or of an array or list of `u8`: `base64_encode("hi")` is `aGk=`, `hex_encode("Hi")` is `4869`. They make text (§9.6). |
| `base64_decode(s)`, `hex_decode(s)` -> `Option<str[N]>` | The bytes that the Base64 or hexadecimal text `s` stands for, as `Option.Some` of a str of the capacity of `s`, or `Option.None` for any other text: Base64 needs a multiple of 4 characters, `=` only in the last two places, and no spaces or newlines; hex an even number of digits, of either case. |
| `url_encode(s)` | `s` with every byte but `A-Z`, `a-z`, `0-9` and `- . _ ~` written `%XX` with uppercase digits (RFC 3986), for a URL path or query part: `url_encode("a b&c")` is `a%20b%26c`. It makes text (§9.6). |
| `url_decode(s) -> Option<str[N]>` | `s` with each `%XX` (either case) replaced by its byte, as `Option.Some` of a str of the capacity of `s`, or `Option.None` when a `%` is not followed by two hexadecimal digits. A `+` stays a `+`. |
| `eq_ignore_case(a, b) -> bool` | Whether two strs are equal but for the case of ASCII letters. |
| `capitalize(s) -> str[N]` | `s` with its first byte uppercase and the rest lowercase (ASCII letters only), of the type of `s`: `capitalize("hELLO")` is `Hello`. |
| `parse_f64(s) -> Option<f64>` | `Option.Some(f64(s))` when `is_float(s)`, else `Option.None`: never traps. |
| `fixed(x, n)`, `pad_left(v, w, fill)`, `pad_right(v, w, fill)` | Formatted text (§9.6). |

**Results typed by where they are stored.** The size of what `split`, `words`, `join`, `replace`,
`split_csv`, `re_replace` and `re_split` (and `fixed`, `pad_left`, `pad_right`, `to_base`, `repeat`,
`variant_name` and `to_json` when they are not written out at once, §9.6) build depends on the data, so each takes its type, capacities included, from the variable it initializes
or is assigned to, or from the function's return type. The call must be the whole right-hand side of a
typed `let`/`var`, a constant, an assignment with `=`, or a `return`, or for `split` and `words`, what a
`for` loop goes through; anywhere else is E0216. A result that does not fit traps (R0004): `split` and
`words` check each piece in turn, first that the list has room for it, then that it fits a `str[K]`;
`join` and `replace` check the whole length first.

**Text that may not fit.** A loop over the pieces never traps on their size or number and builds no list,
so it scales to any input the program accepts:

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
says whether it succeeds. `parse_i64(s)` and `parse_f64(s)` do both at once: an `Option`, `Option.None` for
text the conversion would trap on.

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
| `dedup(mut xs)` | Removes each element of a list (byte of a str) that is `==` to the one before it, keeping the first of each run and the order. After `sort`, every value is left once. |
| `unique(mut xs)` | Removes each element of a list (byte of a str) that is `==` to any before it, keeping the first of each and their order, with no sort needed: `[3, 1, 3, 2, 1]` becomes `[3, 1, 2]`. About n * k comparisons, for k values kept. |
| `next_permutation(mut xs) -> bool` | Rearranges a list, array or str into the next permutation in the order of `sort`, and returns true; from the last (descending) it goes back to the first (ascending) and returns false. Sorted first, `var more: bool = true; while more limit N { ...; more = next_permutation(mut xs); }` visits each distinct order once; equal elements give no repeats. |
| `next_combination(mut idx, n) -> bool` | Advances `idx`, a list or array of k increasing `i64` positions in 0 to n - 1, to the next k of n in lexicographic order, and returns true; from the last one it goes back to `[0, 1, ..., k - 1]` and returns false. Start from `[0, 1, ..., k - 1]`. Traps (R0013) unless `0 <= k <= n`. |
| `sort_by(mut xs, before)` | Sorts a list or array in place into the order of `before`, a function `fn(T, T) -> bool` passed by name that is true when its first argument must come before its second. Stable: elements neither comes before keep their order. A binary insertion sort: about n log2(n) calls of `before`, made in the same order on every backend. |
| `sort_by_key(mut xs, key)` | Sorts a list or array in place by `key(x)`, a function `fn(T) -> K` passed by name, in the order `sort` gives the keys: `sort_by_key(mut people, age)`. Stable, as `sort_by` is. |
| `heap_push(mut h, x)`, `heap_pop(mut h) -> T` | A list kept as a binary heap, a priority queue: `heap_push` adds `x` (traps if full, R0004), `heap_pop` removes and returns the smallest element in the order of `sort` (traps if empty, R0012), and `h[0]` is always the smallest. With a third (second) argument `before`, a function `fn(T, T) -> bool` as for `sort_by`, they order by it; pass the same one to both. About log2(len(h)) comparisons each. |
| `binary_search(xs, x) -> i64` | In an array or list sorted as `sort` sorts it, the position of the first element `e` with `compare(e, x) == 0`, or -1. It looks at about log2(len(xs)) elements; on one that is not sorted it may miss an equal element, the same way on every backend. |
| `any(xs, f) -> bool`, `all(xs, f) -> bool` | Whether `f(x)` is true for some (every) element `x` of an array or list; `f` is a function `fn(T) -> bool` passed by name: `any(xs, is_even)`. They call `f` from the first element on and stop at the first true (false); `any` of an empty one is false, `all` true. |
| `count_if(xs, f) -> i64` | How many elements `x` of an array or list have `f(x)` true. |
| `find_index(xs, f) -> i64` | The position of the first element `x` with `f(x)` true, or -1. |
| `retain(mut xs, keep)` | Keeps the elements `x` of a list for which `keep(x)` is true, in their order, and removes the rest; `keep` is called once per element, first to last. |
| `min_by(xs, before) -> T`, `max_by(xs, before) -> T` | The first element of an array or list that no other comes before (`min_by`), or that comes before no other (`max_by`), in the order of `before` as for `sort_by`: the smallest or largest by any key. `before` is called once per element after the first. Traps if `xs` is empty (R0013). |
| `swap(mut xs, i, j)` | Exchanges the elements at positions `i` and `j` of a list or array (bytes of a str); traps unless both are below `len(xs)` (R0003), `i` checked first. |
| `swap(mut a, mut b)` | Exchanges the values of two places of the same type, of different variables (E0208 for one variable twice: `swap(mut xs[i], mut xs[j])` is `swap(mut xs, i, j)`). |
| `insert(mut xs, i, v)` | Inserts `v` into a list (a `u8` into a str) at position `i`, moving the rest up. Traps unless `0 <= i <= len(xs)` (R0003), then if full (R0004). |
| `remove(mut xs, i) -> T` | Removes and returns the element at position `i` of a list (a byte of a str), moving the rest down. Traps unless `0 <= i < len(xs)` (R0003). Like `pop`, it may be a statement of its own. |
| `extend(mut xs, ys)` | Appends the elements of the array or list `ys` to the list `xs`, in order, each widened to the element type of `xs` as `push` widens it. Traps (R0004) when they do not all fit, before adding any. For text, `append(mut s, t)`. |
| `fill(mut xs, v)` | Sets every element of a list or array (byte of a str) to `v`; a list keeps its length. |
| `rotate(mut xs, k)` | Moves the elements of a list, array or str `k` places toward the front, those that leave the front coming back at the end: `[1, 2, 3, 4, 5]` rotated by 2 is `[3, 4, 5, 1, 2]`. `k` (any integer type) is taken as `rem_euclid(k, len(xs))`, so a negative `k` rotates toward the back; an empty one is left as it is. |
| `is_sorted(xs) -> bool` | Whether the list, array or str `xs` is in the order of `sort`: no element comes after the one that follows it. True for fewer than two. |
| `min_index(xs) -> i64`, `max_index(xs) -> i64` | The position of the element `min(xs)` (`max(xs)`) returns: the first smallest (largest) of a non-empty array or list of numbers, a NaN only when it is first. Traps (R0013) if it is empty. |
| `lower_bound(xs, x) -> i64`, `upper_bound(xs, x) -> i64` | In an array or list sorted as `sort` sorts it, the first position whose element `e` has `compare(e, x) >= 0` (`> 0`), or `len(xs)`: where `x` would be inserted before (after) the elements equal to it, so `upper_bound(xs, x) - lower_bound(xs, x)` counts them. About log2(len(xs)) elements are looked at, as by `binary_search`. |
| `fold(xs, init, f) -> A` | `f(...f(f(init, xs[0]), xs[1])..., xs[n - 1])`: the elements of an array or list combined first to last, with `f` a function `fn(A, T) -> A` passed by name; `init` when it is empty. `A` is the result type of `f`, which a literal `init` takes: `fold(xs, "", f)` with `f` returning a `str[32]`. |
| `transform(xs, f)` | `f(x)` for each element `x` of an array or list, in order, with `f` a function `fn(T) -> U` passed by name: a `list[U; N]` of the length and capacity of the `list[T; N]` `xs`, or a `[U; N]` for an array. |
| `zip(a, b) -> list[(A, B); N]` | The pairs `(a[i], b[i])` of two arrays or lists, as far as the shorter goes, in a list whose capacity `N` is the smaller of theirs (an array's is its length): `for (x, y) in zip(xs, ys) { }`. |
| `prefix_sums(xs) -> T` | The running totals of an array or list of numbers, of its type: element `i` is `xs[0] + ... + xs[i]`, added as `+` adds them: `prefix_sums([1, 2, 3])` is `[1, 3, 6]`. A total that does not fit traps (R0001). |
| `first(xs) -> Option<T>`, `last(xs) -> Option<T>` | `Option.Some` of the first (last) element of an array or list, or byte of a str, or `Option.None` when it is empty. |
| `frequencies(xs) -> map[T, i64; N]` | Each element of an array or list (byte of a str) with the number of times it occurs, in key order; its type is that of the typed `let`/`var`/assignment/`return` it is the whole right side of, as for `split`: `let c: map[str[16], i64; 32] = frequencies(words);`. Traps (R0004) when there are more distinct elements than the map holds. |
| `windows(xs, k)`, `chunks(xs, k)` | In a `for` loop only: each run of `k` consecutive elements of an array, list or str, one element on each time (`windows`), or the elements `k` at a time, the last piece shorter if they do not divide evenly (`chunks`): `for w in windows([1, 2, 3], 2) { }` gives `[1, 2]` and `[2, 3]`, `for c in chunks("abcde", 2) { }` gives `ab`, `cd` and `e`. Each piece is a list (a str for a str) of the capacity of `xs` (an array's is its length), and no list of pieces is built. `windows` gives none when `k > len(xs)`. `for i, w in windows(xs, k)` counts them. Traps (R0013) unless `k >= 1` (any integer type). |

| `insert(mut m, k, v)`, `insert(mut s, x)` | Sets `k` to `v` in a map (as `m[k] = v`), or adds `x` to a set; a key or value already there is kept once. Traps (R0004) when a new one does not fit. |
| `remove(mut m, k) -> bool`, `remove(mut s, x) -> bool` | Removes a key (with its value) or a value; whether it was there. |
| `get_or(m, k, d) -> V` | The value of `k` in the map `m`, or `d` when there is none. |
| `union(a, b)`, `intersection(a, b)`, `difference(a, b)` | The values in either set, in both, or in `a` but not `b`, as a set of the type of `a`; the sets have one element type, of any capacities. A union that does not fit traps (R0004). |
| `symmetric_difference(a, b)` | The values in exactly one of the two sets, as a set of the type of `a`, as for `union`; traps (R0004) when it does not fit. |
| `is_subset(a, b) -> bool` | Whether every value of the set `a` is in the set `b` (one element type, any capacities); true when `a` is empty. |
| `get(m, k) -> Option<V>` | `Option.Some` of the value of `k` in the map `m`, or `Option.None` when there is none: unlike `get_or`, it tells a missing key from one holding the default. |
| `first_key(m) -> Option<K>`, `last_key(m) -> Option<K>` | The smallest (largest) key of the map `m`, or value of the set `m`, or `Option.None` when it is empty. |
| `floor_key(m, k) -> Option<K>`, `ceil_key(m, k) -> Option<K>` | The largest key (value of a set) `<= k`, or the smallest `>= k`, in the order of `sort`, or `Option.None` when there is none: a lookup between the keys. About log2(len(m)) keys are looked at. |
| `keys(m) -> list[K; N]`, `values(m) -> list[V; N]` | The keys of the map `m` in order, or its values in the order of their keys, as a list of the map's capacity. |
| `variants(E) -> [E; K]` | Every variant of the enum named `E` (or `ns.E`), in declaration order: `for c in variants(Color) { }`. The variants carry no values and `E` has no type parameters (E0203). It is a constant: `const ALL: [Color; 3] = variants(Color);`. |

| `compare(a, b) -> i64` | -1, 0 or 1 as `a` comes before, equals or comes after `b` in the order below; two values of any one type, so it orders the values of a type parameter. |

`len`, `cap`, `clear` and `contains` work on maps and sets too, by key. `contains`, `index_of`, `last_index_of`, `count` and `slice` (§9.4) work on arrays and lists too. Positions (`i`, `from`, `to`) may
be of any integer type, as indexes may.

**The order of `sort`** is defined for every type: numbers by value, with `-0.0` before `0.0` and every
NaN after everything else; `false` before `true`; strs (by unsigned byte), arrays and lists element by
element, a prefix before a longer value (sets too; maps entry by entry, key then value); tuples and structs field by field, in the order they are declared; enums
by the order of their variants, then by payload. So a list of structs sorts by its first field, then its
second: put the key first, and sort on a negated key, or `reverse` afterwards, for descending order. Two
values this order calls equal cannot be told apart, so the result is the same whatever the algorithm,
and no sort is stable or unstable in a way a program can see. For any other order, `sort_by` takes a function
of your own, and is stable.

The function passed to `any`, `all`, `count_if`, `find_index`, `retain`, `sort_by`, `min_by`, `max_by`, `fold`
or `transform` follows the rules of a parameter of a function type (§5): a function of exactly the type wanted
(its parameters the element type, and `bool` back; for `fold` the accumulator and the element type, and the
accumulator back; for `transform` the element type, and anything back), passed by its name, or a parameter of a
function type passed on. It is called once per element, first to last (`any`, `all` and `find_index` stop
early). A trap in it stops the call where it is, as a trap in any call does.

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

These functions make text: with a width, a number of decimals or a base, digits in groups, a pattern
repeated, the name of a variant, JSON, and the encodings `base64_encode`, `hex_encode` and `url_encode`
(§9.4):

| Function | Text |
|----------|------|
| `fixed(x, n)` | The f32 or f64 `x` with exactly `n` digits after the point, 0 to 20 (otherwise R0013), and no point for 0: its exact binary value rounded half to even, as above. `fixed(3.14159, 2)` is `3.14`, `fixed(2.5, 0)` is `2`, `fixed(2.675, 2)` is `2.67` (2.675 is stored as 2.67499...); `nan`, `inf`, `-inf`. |
| `pad_left(v, w, fill)` | The text of the number, bool, str or `fixed(...)` `v`, as `print` writes it, with the `u8` byte `fill` in front up to `w` bytes; longer text is kept whole. `pad_left(7, 3, '0')` is `007`, `pad_left(-42, 6, '0')` is `-00042`: zeros padding a negative number, `fixed(...)` or `to_base(...)` go after its sign. A width above 1 048 576 traps (R0013). |
| `pad_right(v, w, fill)` | The same with the fill after the text: `pad_right("ab", 4, '.')` is `ab..`. |
| `pad_center(v, w, fill)` | The same with the fill split around the text, the odd byte after it: `pad_center("ab", 5, '*')` is `*ab**` (Python's `str.center` puts it before for some lengths). |
| `group_digits(n, sep)` | The integer `n` in decimal with the pattern `sep` (a str or `u8` byte) between each group of three digits from the right, after any `-`: `group_digits(1234567, ',')` is `1,234,567`, `group_digits(-1000, ' ')` is `-1 000`. |
| `to_base(n, base)` | The integer `n` in a base from 2 to 36 (otherwise R0013), with the digits `0`-`9` then `a`-`z`, no prefix, and a `-` before a negative `n`: `to_base(255, 16)` is `ff`, `to_base(-5, 2)` is `-101`. `pad_left(to_base(b, 16), 2, '0')` gives two hex digits. |
| `variant_name(e)` | The name of the variant of the enum value `e`: `variant_name(Color.Red)` is `Red` (§9.4). |
| `to_json(v)` | The JSON text of `v` (§9.4): `print(to_json(order));`. |
| `repeat(p, n)` | The pattern `p`, a str or `u8` byte, `n` times: `repeat("ab", 3)` is `ababab`, `repeat('-', 20)` a rule. Traps (R0013) unless `0 <= n <= 1048576` (`n` of any integer type), and when the text would be longer than 1 048 576 bytes. |

They build no str when they are an argument of `print` or `append`, or the value `pad_left` or
`pad_right` pads (the others too): the text is written straight out, `append(mut s, "$", c / 100, ".", pad_left(c % 100, 2, '0'))`.
Anywhere else they take the type of a typed str they are stored in, as `join` does (§9.4), and trap
(R0004) when the text does not fit it: `let cell: str[8] = pad_left(n, 6, ' ');`. Elsewhere they are
E0216. Their arguments are evaluated and checked before any of the text is written.

### 9.7 Dates

Dates are counted in days since 1970-01-01, an `i64`, in the Gregorian calendar, years 1 to 9999. Days
subtract to give the days between two dates and add to move one; nothing reads a clock (§8).

| Function | Description |
|----------|-------------|
| `days_from_date(year, month, day) -> i64` | The days of a date, three `i64`: `days_from_date(1970, 1, 2)` is 1, `days_from_date(1969, 12, 31)` is -1. Traps (R0013) unless the date exists: `days_from_date(2026, 2, 29)` traps. |
| `date_from_days(days) -> (i64, i64, i64)` | The date `(year, month, day)` of a day: `let (y, m, d): (i64, i64, i64) = date_from_days(n);`. Traps (R0013) outside -719162 (0001-01-01) to 2932896 (9999-12-31). |
| `weekday(days) -> i64` | The day of the week, as ISO 8601 numbers them: 1 for Monday to 7 for Sunday; `weekday(0)` is 4, a Thursday. Any `i64`. |
| `parse_date(s) -> Option<i64>` | The days of the date written `YYYY-MM-DD` in `s`, exactly ten bytes, or `Option.None` for other text or a date that does not exist. |
| `format_date(days) -> str[10]` | The date as `YYYY-MM-DD`, as `parse_date` reads it: `format_date(0)` is `1970-01-01`. Traps (R0013) outside the years 1 to 9999, as `date_from_days` does. |
| `is_leap_year(y) -> bool` | Whether the year `y` (any `i64`) has a February 29: divisible by 4, and by 400 if by 100. |
| `days_in_month(y, m) -> i64` | The days of month `m` of year `y`, 28 to 31. Traps (R0013) unless `1 <= m <= 12`. |
| `add_months(days, n) -> i64` | The date `n` months later (earlier for a negative `n`), on the same day of the month, or on the last day of a shorter month: from 2026-01-31, one month on is 2026-02-28. Traps (R0013) unless both dates are in the years 1 to 9999. |

Times are counted in seconds since 1970-01-01T00:00:00Z, an `i64`, in UTC, with no leap seconds: a day is
86 400 of them, so `floor`-dividing by 86400 (`div_euclid`) gives the day.

| Function | Description |
|----------|-------------|
| `parse_datetime(s) -> Option<i64>` | The seconds of an ISO 8601 / RFC 3339 date and time: `YYYY-MM-DD`, `T` (or `t` or a space), `HH:MM` or `HH:MM:SS` with any fraction of a second (dropped), then `Z` (or `z`), `+HH:MM`, `-HH:MM`, or nothing for UTC: `parse_datetime("2026-11-27T10:30:00+01:00")` is 1795771800. `Option.None` for any other text, or a date or time that does not exist (hours 0 to 23, minutes and seconds 0 to 59). |
| `format_datetime(t) -> str[20]` | The instant as `YYYY-MM-DDTHH:MM:SSZ`, in UTC: `format_datetime(0)` is `1970-01-01T00:00:00Z`. Traps (R0013) outside the years 1 to 9999. |

```alang
test "thirty days after a date" {
    let start: Option<i64> = parse_date("2024-02-15");
    assert format_date(unwrap_or(start, 0) + 30) == "2024-03-16";
    assert weekday(days_from_date(2024, 3, 16)) == 6;
    assert format_date(add_months(days_from_date(2024, 1, 31), 1)) == "2024-02-29";
}

test "an instant in another zone" {
    let t: Option<i64> = parse_datetime("2026-11-27T10:30:00+01:00");
    assert format_datetime(unwrap_or(t, 0)) == "2026-11-27T09:30:00Z";
}
```

## 10. Tools: calling ALang from an agent

The `tool fn`s of the file given on the command line (not of its imports, §3.1) can be called with JSON.
`alang tools file.al` prints Anthropic-style definitions: `name`, `description` (the `///` comment) and
`input_schema`, whose `description`s come from the doc comments of the parameters and of the structs,
enums, fields and variants they use (a documented payload-less variant is a `{"const": "Variant"}`
alternative of its own).

The description also lists each `requires` clause, the return type and each `ensures` clause, one per line.
A `requires` that compares a parameter, a field of one, or the `len` of either with a constant expression
also narrows the schema: `requires people > 0 and people <= 16` gives `"minimum": 1, "maximum": 16`; a
list `len` bound sets `minItems`/`maxItems`; on a `str` (bytes vs. JSON characters) an upper bound sets
`maxLength` and a lower one only `"minLength": 1`. The schema never rejects a call the contract accepts,
and the call still checks every clause (R0006). `input_examples` holds up to three distinct argument
objects from calls of the tool in `test` blocks with all-constant arguments, in order, each run first
(within `--max-steps`) and kept only if its result is JSON-representable.

`alang call file.al name '{"arg": ...}'` prints `{"ok": true, "result": ..., "stdout": "...", "steps": N}`
or `{"ok": false, "error": {...}}`. `-` reads the arguments from stdin (UTF-8 only); none means `{}`.
`stdout` keeps the first 1 MiB, then adds `"stdout_truncated": true` and `"stdout_bytes"`. `steps` is the
deterministic cost (§8), reported also for a trap or `bad_result`, not for a call refused before running.
`--timeout SECONDS` (60; 0 for none) is a machine-dependent backstop, not a trap:
`{"ok": false, "error": {"code": "timeout", ...}, "stdout": "...", "steps": N}`.

`alang fuzz file.al` calls every tool, and a `fn main(input: str[N])`, with the first `input_examples` (if
any), then varies one parameter at a time within its bounds: extreme numbers, `0`, `1`, `-1`; empty and
full strs of many shapes (one long word, distinct words with various separators, "name number" and
"key: value" lines, rows of 1 to 6 fields, numbers, separators alone); empty, one-element and full
collections, full ones also of extreme numbers; every enum variant; each struct field. It reports each
distinct trap (code and place) with the first arguments that hit it, exiting 1 if any. A failed `requires`
of the tool itself is left out; one of a function it calls is not. At most 200 calls per tool, the same
on every run, each within `--max-steps`.

| ALang type | JSON |
|------------|------|
| `bool` | boolean |
| integers | integer, range-checked (`minimum`/`maximum` in the schema) |
| floats | number; JSON has no NaN or infinity, so a result holding one fails with `bad_result`, and an argument too large for the type is rejected |
| `str[N]` | string of at most N UTF-8 bytes; a result that is not UTF-8 text fails with `bad_result` |
| `[T; N]` | array of exactly N items |
| `list[T; N]` | array of at most N items |
| struct | object with every field required but those with a default and those of an `Option` type, no extra fields |
| `(T1, T2)` | array of exactly that many items, each of its element's type (`prefixItems`) |
| `map[str[K], V; N]` | object of at most N properties, each a value of V |
| `map[K, V; N]` (other keys) | array of at most N `[key, value]` pairs, each key once |
| `set[T; N]` | array of at most N distinct items, in any order |
| `Option<T>` | `null` for `Option.None`, the JSON of the value for `Option.Some(value)`; an `Option<Option<T>>` keeps the enum form, as `null` could be either |
| enum | `"Variant"` without payload, `{"Variant": [v1, v2]}` with |

Arguments must be strict JSON (RFC 8259) and are validated against the parameter types before the call;
unknown, missing or duplicate keys are errors. A parameter with a default (§5) is optional: left out of
`required`, shown as `"default"` (unless JSON cannot write it), and supplied when absent. So is one of an
`Option` type, which is `Option.None` when absent, as for `null`.

`alang serve file.al` serves the tools over MCP's stdio transport (JSON-RPC 2.0, one message per line,
until stdin ends). It answers `initialize` (revisions 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05;
otherwise the newest), `ping`, `tools/list` and `tools/call` (and, with `--skills`, the methods of skills,
below), and ignores notifications. Tools carry the
`alang tools` schema and description, an `outputSchema` `{"result": <return type schema>}` when they return
a value, and the annotations read-only, not destructive, idempotent, closed-world (only open-world for one that
asks the host, §10.1). A call's text is what
`alang call` prints; a successful value is also `structuredContent` `{"result": value}`; any failure is a
result with `"isError": true`. `"arguments"` may be absent or `null`. JSON-RPC errors: an unknown tool
(-32602) and a reused id of a pending call (-32600). A program with errors starts no server; diagnostics
go to stderr, the only other output.

`--watch` polls the program's files (imports included, even missing ones) and serves a new version once
two polls 0.3 s apart agree and it compiles; each request uses the version current when read.
`initialize` reports `"listChanged": true`, and a change to the tool definitions sends
`notifications/tools/list_changed` after `notifications/initialized`. A version with errors is reported on
stderr and the previous one stays; each reload is logged.

Up to `--workers` (4) calls run at once, each on its own thread and still deterministic; up to
`--max-queued` (256) wait, and more are refused with -32001 (server busy). Other requests, and
`bad_arguments`, are answered at once; answers may come out of order. Each call gets `--max-steps` and
`--timeout`. `notifications/cancelled` stops a running or waiting call, which gets no answer (a numeric
`requestId` matches by value; one for an ended call is ignored). When stdin ends, the server finishes its
calls and exits.

Tasks: a client that offers the `io.modelcontextprotocol/tasks` extension, in `initialize`'s
`capabilities.extensions` or in a request's `_meta["io.modelcontextprotocol/clientCapabilities"].extensions`,
gets a task for a call still running (or waiting for a worker) after 1 s: the request is answered with
`{"resultType": "task", "taskId": "task-1", "status": "working", "createdAt", "lastUpdatedAt", "ttlMs",
"pollIntervalMs": 2000}`, and its id is free again. A call that ends sooner is answered as usual.
`tasks/get {"taskId"}` gives the task with `"resultType": "complete"`; once the call ends its `status` is
`completed` with `result`, the `tools/call` result it would have had (a failing call is `completed` with
`"isError": true`), `failed` with `error` if the server failed to run it, or `cancelled`. `tasks/cancel
{"taskId"}` stops it. Tasks are per session; a finished one is kept an hour, and for `ttlMs` from its creation
(`--timeout` plus an hour; `null` without a time limit), then forgotten, as are the oldest beyond 1024 finished
ones; an unknown task is -32602. `initialize` lists the extension under `capabilities.extensions`. Once stdin
ends, calls are answered as they end, not as tasks.

`--http [HOST:]PORT` serves MCP's Streamable HTTP transport at `http://HOST:PORT/mcp` (HOST defaults to
127.0.0.1). Each `POST /mcp` carries one message: a request gets one `application/json` response when its
call ends; a notification or response gets 202. `initialize` returns an `Mcp-Session-Id` that later
messages must carry (400 without, 404 once ended); ids and cancellations are per session, and a cancelled
request gets -32800. `DELETE /mcp` ends a session and its calls; sessions idle for an hour end, at most
1024 are open. The server sends nothing of its own: `GET /mcp` is 405 and `listChanged` false. An
unsupported `MCP-Protocol-Version` is 400; a body over 4 MiB is 413.

`--token-file FILE` requires `Authorization: Bearer TOKEN` on every request but `GET /health` (TOKEN: the
file's first line, at least 16 characters, compared in constant time); without it the server listens only
on loopback. A request with an `Origin` is refused (403) unless `--allow-origin` names it, or it is local
and the server listens only locally. `GET /health` returns `{"status": "ok", "version": ..., "tools": n, "skills": n}`;
`GET /metrics` gives Prometheus counters `alang_tool_calls_total{tool, outcome}`,
`alang_tool_call_steps_total`, `alang_tool_call_seconds_sum`/`_count`, `alang_tool_calls_in_flight`,
`alang_sessions`, `alang_http_requests_total{status}`. Each request and call is logged on stderr as a JSON
line (`"event": "request"` or `"call"`). SIGINT or SIGTERM stops accepting requests and finishes the calls
under way.

`--tls-cert FILE --tls-key FILE` serve HTTPS at `https://HOST:PORT/mcp`, TLS 1.3 only: a client that offers
nothing newer fails the handshake. The certificate file holds the PEM certificate chain, the server's own
certificate first (a `fullchain.pem`); the key file its private key in unencrypted PKCS#8 PEM
(`-----BEGIN PRIVATE KEY-----`): RSA, EC or Ed25519. A key in another format (encrypted, `RSA PRIVATE KEY`,
`EC PRIVATE KEY`) is refused with the command that converts it, `openssl pkey -in KEY -out key.pem`, and a
key that is not the certificate's is refused too, both before the server starts. Both files are read once, at
start: restart the server to serve a renewed certificate. The `"listening"` log line says `"tls": "TLSv1.3"`
(or `false`); listening beyond loopback without TLS adds a `"warning"`, as the token then crosses the network
in plain text (unless a proxy in front terminates TLS).

Skills: `--skills DIR` (more than once for several) serves [Agent Skills](https://agentskills.io) beside the
tools, by MCP's Skills extension, `io.modelcontextprotocol/skills`, which `initialize` then lists under
`capabilities.extensions` with `{"directoryRead": true}`, beside `capabilities.resources`. A directory that
holds a `SKILL.md` is one skill, served as `skill://NAME/...` (NAME: the directory's name); any other DIR is a
tree in which each directory holding a `SKILL.md` is a skill at its path, `DIR/acme/refunds/SKILL.md` being
`skill://acme/refunds/SKILL.md` (a skill inside another is one too, and its files are also the outer one's).
Each file under a skill's directory is a resource at `skill://<skill path>/<file path>`, each segment
percent-encoded but for `A-Z a-z 0-9 - . _ ~`; names beginning with `.` are left out, and so are files in no
skill. Every file is read at start, so what is listed is what a read returns. A skill is refused, and the
server does not start (exit 2), when its `SKILL.md` is not UTF-8 or does not begin with YAML frontmatter
between two `---` lines, when the frontmatter's `name` is not the directory's name or not 1 to 64 lowercase
letters, digits and single hyphens, when it has no `description` of 1 to 1024 characters, past 512 files or
16 MiB, when a URI would be served from two directories, at a symbolic link, or when no skill is found.

The frontmatter is sent as JSON, verbatim, so a host can match it against what its own YAML parser reads:
block mappings and sequences, plain scalars (also over several lines), quoted strings, `|` and `>` block
scalars, one-line flow collections and comments are read as YAML 1.2 reads them (`true`, `12`, `0x1F`, `1.5`
and `null` are typed; other text is a string). A value that YAML 1.1 parsers, such as PyYAML, read otherwise
is refused with the line, to be quoted: `yes`, `no`, `on`, `off`, `010`, `0o17`, `1e3`, `1_000`, `1:30`,
`-.5`, a date. So is a plain value holding `: ` (`description: Use when: ...`, which YAML rejects), and anchors,
aliases, tags, `.inf`, `.nan` and tabs in the indentation.

- `skills/list` gives `{"resultType": "complete", "skills": [...], "ttlMs", "cacheScope"}`, each entry
  `{"uri": ".../SKILL.md", "frontmatter": {...}, "resources": [{"uri", "digest": "sha256:<hex>", "size"}]}`:
  every file of the skill, `SKILL.md` included, in URI order. `skills/get {"uri"}` gives one as `"skill"`.
- `resources/list` lists every file (`uri`, `name`, `mimeType`, `size`; a `SKILL.md` is named and described as
  its skill); `resources/read {"uri"}` gives `contents`, one item with `text`, or `blob` (Base64) for bytes
  that are not UTF-8; `resources/templates/list` is empty.
- `resources/directory/read {"uri"}` gives the direct children of a directory (`skill://acme`,
  `skill://acme/refunds`, `skill://acme/refunds/templates`, no trailing `/`), a subdirectory with
  `"mimeType": "inode/directory"`.
- Each result has `"resultType": "complete"`, `"ttlMs": 300000` (0 with `--watch`) and `"cacheScope":
  "private"`, so that a shared cache never hands what `--token-file` guards to a client without the token.
  An unknown skill, file or directory, or a missing `uri`, is -32602; without `--skills` these methods are
  -32601.
- The `instructions` name the skills' URIs (the first 16) and say to read one when its task comes up.
- With `--watch` the directories are polled with the program and a change is served once two polls agree:
  `"listChanged": true` under `resources`, and `notifications/resources/list_changed` after
  `notifications/initialized`. Skills that cannot be served are logged once on stderr, and those before stay.

### 10.1 Host functions

A `host fn` (§3) is the one way a program reaches outside data: it has no body, and the program's host answers
each call. The call sends the arguments as a JSON object, one entry per parameter in order, written as `to_json`
writes them (a NaN or a str that is not UTF-8 traps R0013 first); the host replies with the JSON of the result,
mapped as a tool's arguments are (the table above), or anything for a host fn without a result. The host fn's
`requires` are checked before the call and its `ensures` against the reply, so a contract states what the
program counts on the host to send. No reply traps R0015; a reply that is not of the result's type traps R0016
(the message says where it differs). For failures the program should handle, return a `Result`:

```alang
/// The price of an item in cents, or why there is none.
host fn price(sku: str[16]) -> Result<i64, str[64]>
    ensures not is_ok(result) or unwrap_or(result, 0) >= 0;

/// The total of an order; an item without a price adds nothing.
tool fn total(skus: list[str[16]; 8]) -> i64 {
    var sum: i64 = 0;
    for s in skus {
        sum += unwrap_or(price(s), 0);
    }
    return sum;
}

fn price_list(sku: str[16]) -> Result<i64, str[64]> {
    if sku == "tea" {
        return Result.Ok(350);
    }
    return Result.Err("not sold");
}

test "adds the prices it gets" with price = price_list {
    assert total(["tea", "gold", "tea"]) == 700;
}
```

What answers:

- **In a test**, the function `with` binds each host fn to, called directly; an unbound host fn gets no answer.
- **`run`, `call`, `serve` and `debug`**, the commands of `--hosts FILE`, a JSON object from each host fn's
  name (or `"*"` for any other) to `{"command": [program, arguments...], "timeout": seconds}` (timeout 10
  by default). Each call runs the command once, in the file's directory (a relative program path such as
  `./host` is too), with `{"fn": "price", "args": {"sku": "tea"}}` and a newline on its standard input. It
  prints the JSON of the result on its standard output, at most 1 MiB, and exits with status 0. Any other
  status, no answer within the timeout, or output that is not JSON fails the call, with what the command
  wrote on its standard error.
- **`run`, `call` and `debug`**, `--replay FILE`: the calls a run recorded, answered again in order; a call
  with another name or other arguments than the next one recorded gets no answer.
- **`fuzz`**, the fuzzer: each host fn replies with values of its result type at their limits, as a tool's
  arguments are chosen, first with the plainest value and then, in up to 200 more calls, each value in turn
  with each set of arguments. A reply that breaks the host fn's own `ensures`, which the program says the
  host never sends, is left out, as a tool's own failed `requires` is.
- **The Java API**, `CallOptions.host(Host)`, a function from the host fn's name and arguments (JSON text) to
  the result (JSON text), which fails the call by throwing; `Host.replay(json)` answers from a recording.
- **The C backend**, `al_host` (§12).

Every call the host is asked is recorded: `call` and `run --json` add `"host_calls"`, after `"result"`
(`"error"`, `"trap"`) when there were any, each `{"fn": name, "args": {...}, "result": reply}`, or
`"error": why` for a call that got no reply. The same arguments and the same replies give the same run, so a
report passed to `--replay` repeats the call exactly (§8). A tool whose calls may reach a host fn says so in its
description (`Asks the host (price), so it may reach outside data, and a repeated call may differ.`); `serve`
gives it only the annotation `"openWorldHint": true`, as what the host does is not known, and adds a sentence
about it to its instructions.

## 11. Runtime errors (traps)

A trap stops the program (or the current test or tool call) and reports a code `R0001`–`R0016`, a
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
- A `host fn` (§10.1) calls `int al_host(const char *name, const uint8_t *args, uint32_t args_len, uint8_t
  *reply, uint32_t reply_cap, uint32_t *reply_len)` with its arguments as JSON (at most 1 MiB, in static
  storage); it writes the JSON of the result to `reply` and its length to `*reply_len` and returns 1, or
  returns 0 when it has no answer (R0015). The program the C file is linked with defines it, with
  `-DAL_HOST`: `cc -std=c99 -DAL_HOST file.c host.c -lm`. Without `-DAL_HOST` no call gets an answer.

Recommended: `cc -std=c99 -O2 file.c -lm`. The test suite checks gcc and clang at `-O0` to `-O3`.

## 13. The debugger

`alang debug file.al` runs `fn main` under a debugger that reads commands from stdin, one per line.
`--test NAME` runs the test whose name is NAME, or the one test whose name contains it, instead, and
`--call TOOL '{"n": 4}'` a call of a tool with those JSON arguments. A `fn main(input: str[N])` gets the
bytes of `--input FILE`, or none, since stdin carries the commands. `--break LOC`, once or more, sets
breakpoints before the first command.

| Command | Short | What it does |
|---------|-------|--------------|
| `break LOC` | `b` | Stops at LOC: a line of the main file (`12`), a line of an imported one (`lib/fmt.al:12`), or a function (`add_word`), at the first statement of each call. A line must have a statement start on it; if it does not, the error names the nearest lines that do. `break LOC if COND` stops only where the bool expression COND is true: `break 12 if i == 3 and len(xs) > 0`. |
| `delete [N]` | `d` | Deletes breakpoint N, or every one. |
| `breakpoints` | | Lists the breakpoints, with how often each was hit. |
| `run` | `r` | Starts the program, which then stops at breakpoints and traps. |
| `continue` | `c` | Runs to the next breakpoint or trap, or the end. |
| `step` | `s` | Runs to the next statement, in a function the current one calls if it calls one; before the program starts, to its first statement. |
| `next` | `n` | Runs to the next statement of the current function, or of its caller once it returns. |
| `finish` | `f` | Runs until the current function returns, to the next statement of its caller. |
| `where` | `bt` | The call stack, innermost first, with the statement each frame is at. |
| `frame N` | | Looks at frame N (0 is the innermost) for `locals`, `print` and `list`. |
| `locals` | `v` | The frame's parameters and the variables in scope, with their types and values. |
| `print EXPR` | `p` | The value of an expression, in the scope of the frame: `xs[i].name`, `len(xs) - 1`, `total(xs)`, `m["key"]`, `match k { 3 => "three", _ => "other" }`. |
| `list [LINE]` | `l` | The source around the stop, or around LINE. |
| `display` | | Also shows the variables at every stop; again to stop showing them. |
| `help` | `h` | The commands. |
| `quit` | `q` | Ends the session, and the program with it. |

A stop is before a statement runs. A line breakpoint stops where the run reaches its line anew: at a
statement starting on the line, unless the previous statement of the frame started on it too. So `a; b;`
on one line stops once, a loop body on its own lines once per iteration, and a one-line
`for i in 0..3 { n += i; }` as the loop starts and each time round.

A `print` or condition expression is checked as if written at the frame's statement (its variables in
scope, the program's constants and functions), then evaluated on a copy of the frame with its own budget
of 10 million steps. A call with a `mut` argument is refused (E0215), so it cannot change the program; what
it prints is shown with its value. A condition that does not check where it is reached is reported once
there as `condition_skipped` and does not stop; one that traps stops and says so. A variable is in scope
from the statement after its declaration to the end of its block; loop variables in the body, match
bindings in the arm. A trap always stops before the stack unwinds; resuming then ends the program with the
trap, as `run` reports it. Strs are shown in quotes; values over 2000 characters are cut.

When the commands run out, as they do at once with `< /dev/null`, the program runs on: each later stop is
shown with the variables in scope and passed, up to `--max-stops` (1000), after which it runs to its end
without stopping. So `alang debug file.al --break 12 < /dev/null` prints the state at line 12 every time
it is reached.

With `--json`, every line of output is one JSON object with an `"event"`: `breakpoint` when one is set,
`condition_skipped`, `stopped` (with `"reason"` `breakpoint`, `step` or `trap`; a `condition_error` when the condition trapped; `file`, `line`, `column`, `source_line`,
`function`, `steps`; a trap's `code` and `message`; and `variables`, each `{"name", "type", "value"}`,
when the variables are shown), `output` with the text the program printed since the last event, `stack`,
`frame`, `variables`, `value` (the `expression`, its `type` and `value`, and any `output`), `source`, `breakpoints`, `deleted`, `display` and `error` (`message` and
`hint`) in answer to commands, and last `exited`: `"ok": true` with the steps and a tool's `result`, or
`"ok": false` with the `trap` as `run --json` reports it. Without `--json`, the program's output and the
debugger's share stdout, in the order they happen.

The debugger only reads: a run under it prints the same output, takes the same steps and traps at the same
place as `alang run`, and the same program, commands and input give the same transcript. The exit code is
0 when the program ran to its end, 1 when it trapped or a `quit` stopped it, and 2 for a usage error.
