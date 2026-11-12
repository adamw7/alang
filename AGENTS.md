# Writing ALang: a guide for AI agents

ALang is built for you: small, explicit and strict, and every mistake comes back as a coded diagnostic
with a concrete fix. This page covers everything you need; the full rules are in
[docs/SPEC.md](docs/SPEC.md), the grammar in [grammar/ALang.g4](grammar/ALang.g4), every error code in
[docs/ERRORS.md](docs/ERRORS.md).

## The loop

```sh
bin/alang check prog.al --json    # 1. diagnostics: code, line, column, message, hint
bin/alang test  prog.al --json    # 2. run every `test "..." { }` block
bin/alang fuzz  prog.al           # 3. call each tool fn, and a main that reads input, at its limits
bin/alang run   prog.al           # 4. run fn main()
bin/alang run   prog.al < in.txt  #    a fn main(input: str[N]) gets all of stdin
bin/alang explain E0215           #    explain any code
```

Fix the **first** diagnostic first, using its `hint`, and re-run `check` after every edit. Results are
deterministic: a test that fails once fails every time, identically. `fuzz` tries the inputs your tests
did not: empty and full strs of many shapes, full lists, the smallest and largest numbers. Each trap it
reports comes with the arguments that caused it; handle that input, or narrow it with `requires`.

No `bin/alang` yet? Unpack `alang-VERSION.tar.gz` from <https://github.com/adamw7/alang/releases/latest>
and use its `bin/alang` (`bin\alang.cmd` on Windows); it needs only Java 21 or newer.

## A complete program

```alang
// Word frequencies without a heap: everything has a fixed capacity.
const MAX_WORDS: i64 = 16;

struct Entry {
    word: str[16],
    count: i64,
}

enum Found {
    At(i64),
    Missing,
}

fn find(entries: list[Entry; MAX_WORDS], word: str[16]) -> Found {
    for i in 0..len(entries) {
        if entries[i].word == word {
            return Found.At(i);
        }
    }
    return Found.Missing;
}

fn add_word(entries: mut list[Entry; MAX_WORDS], word: str[16]) {
    match find(entries, word) {
        Found.At(i) => { entries[i].count += 1; }
        Found.Missing => {
            if len(entries) < MAX_WORDS {
                push(mut entries, Entry(word: word, count: 1));
            }
        }
    }
}

/// Counts how often each space-separated word occurs: the first 16 distinct words, each cut to 16 bytes.
tool fn word_counts(text: str[256]) -> list[Entry; MAX_WORDS] {
    var entries: list[Entry; MAX_WORDS] = [];
    var word: str[16] = "";
    for c in text {
        if c == ' ' {
            if len(word) > 0 {
                add_word(mut entries, word);
                clear(mut word);
            }
        } else if len(word) < cap(word) {
            push(mut word, c);
        }
    }
    if len(word) > 0 {
        add_word(mut entries, word);
    }
    return entries;
}

fn main() {
    for e in word_counts("the cat saw the dog") {
        print(e.word, ": ", e.count);
    }
}

test "counts repeated words" {
    let counts: list[Entry; MAX_WORDS] = word_counts("a b a");
    assert len(counts) == 2;
    assert counts[0] == Entry(word: "a", count: 2);
}
```

## Rules to remember

1. **Every variable has a type and a value**: `let x: i32 = 0;` (immutable) or `var x: i32 = 0;` (mutable).
2. **No implicit conversions.** `i64(x)`, `f64(n)`, `u8(c)`. Integer literals adapt to context and default to `i64`; `for i in 0..n` gives `i: i64` when the bounds are literals. `len()` returns `i64`.
3. **No heap, no null, no pointers.** Fixed-capacity types: `str[64]`, `list[T; 16]`, `[T; 4]`, `map[K, V; 16]`, `set[T; 16]`. Capacity overflow traps.
   A smaller capacity widens on its own; to narrow, convert: `str[16](s)`, `list[T; 8](xs)` (traps if it
   does not fit, so check `len` first). A parameter takes any capacity with `_`: `fn total(xs: list[i64; _])`,
   `fn shout(s: mut str[_])` work on every list of i64 and every str; inside, `cap(s)` is the caller's.
4. **Structs use named arguments**: `Point(x: 1, y: 2)`. Never `Point { x: 1 }`.
5. **Enum variants are qualified with a dot**: `Color.Red`, `Shape.Circle(2.0)`. Never `Color::Red`.
6. **Logic uses words**: `and`, `or`, `not`. `&&`, `||`, `!x` are errors (`!=` is fine).
7. **No precedence guessing**: `* / %` bind tighter than `+ -`; everything else needs parentheses when mixed: `(a and b) or c`, `(flags & 4) != 0`, `(x << 2) + 1`. Comparisons do not chain: `0 < x and x < 10`.
8. **`while` needs a limit**: `while lo < hi limit 64 { }`. Exceeding it traps. Prefer `for i in 0..n` or `for x in xs` when you can:
   ranges also go `0..=n`, `step 2` and `rev 0..n` (backwards), and `for i, x in xs` gives each index.
9. **No recursion.** Use a loop with an explicit work list (`list[T; N]` as a stack or queue).
10. **`mut` is explicit on both sides**: `fn grow(xs: mut list[i32; 8])` and `grow(mut xs)`. Plain parameters are read-only copies.
    So is every variable: `var it: Item = items[0]; it.qty += 1;` changes the copy, not the list (warning
    W0305); write `items[0].qty += 1;`.
11. **A call with a `mut` argument is its own statement** or the whole right side of `let`/assignment/`return`: `let top: i32 = pop(mut stack);` then use `top`.
12. **A `mut` variable cannot appear twice in one call**: `append(mut s, s)` and `swap(mut xs[i], mut xs[j])` are errors. Numbers passed without `mut` are copied first, so `remove(mut xs, len(xs) - 1)` is fine.
13. **No shadowing.** Pick a new name instead of redeclaring one in an inner block.
14. **`match` must be exhaustive.** Add `_ => { }` last when matching integers or strings. An arm may have a guard,
    `MaybeI64.Some(v) if v > 0 => { }`; guarded arms do not count toward exhaustiveness.
15. **Functions with a return type return on every path.** Add a final `return` after loops.
16. **No methods.** `len(xs)`, not `xs.len()`; `push(mut xs, v)`, not `xs.push(v)`; `trim(s)`, not `s.trim()`.
17. **Strings are byte buffers**: `s[i]` is a `u8`, `'a'` is a `u8` constant. Build text with `append(mut s, "x = ", x)`; add one character with `push(mut s, 'a')`. Two strs compare with `==` and order with `<`, byte by byte. For characters beyond ASCII, `char_count(s)` counts
    code points, `for c in chars(s) { }` goes through them (each a `u32`), and `slice_chars` cuts between them.
18. **No exceptions.** Return an enum for recoverable failures; `panic("...")` or `assert` for bugs.
19. **Input arrives whole; nothing else is read.** `fn main(input: str[4096])` gets all of stdin as bytes
    (`bin/alang run prog.al < data.txt`; more than 4096 bytes traps), a `tool fn` gets JSON. No files.
    Go through it with `for line in split(input, '\n') { }`: one line at a time, never a trap on a long one.
    Split a line into a list with `try_split(mut fields, line, ',')`: false, not a trap, when it does not fit.
20. **`import "lib/text.al";` at the top of a file** adds that file's declarations, by their plain names.
    The path is relative to the importing file, with `/`. Its `fn main`, tests and tools stay its own.

## Translating habits from other languages

The rules above cover structs, enums, logic, conversions, `while`, methods, `mut` and recursion. Also:

| You might write | Write in ALang |
|-----------------|----------------|
| `let x = 5;` / `x := 5` | `let x: i64 = 5;` |
| `int`, `usize`, `String`, `Vec<T>` | `i32`/`i64`, `i64`, `str[N]`, `list[T; N]` |
| `Option<T>`, `null` | `enum MaybeT { None, Some(T) }` |
| `dict`, `HashMap<K, V>`, `set()`, `HashSet<T>` | `map[str[16], i64; 64]`, `set[i64; 32]`: a capacity, keys always in order; `var m: map[str[8], i64; 16] = [];` then `m[k] = v;` |
| `d.get(k, 0)`, `d[k] = d.get(k, 0) + 1`, `for k, v in d.items()` | `get_or(m, k, 0)`, `m[k] = get_or(m, k, 0) + 1;`, `for k, v in m { }` |
| `return a, b`, `(a, b) = f()`, `let (q, r) = divmod(a, b)` | `return (a, b);` with `-> (i64, i64)`, then `let (q, r): (i64, i64) = divmod(a, b);` and `p.0`, `p.1` |
| `i++` | `i += 1;` |
| `for (i = 0; i < n; i++)` | `for i in 0..n { }` |
| `for i in range(0, n + 1, 2)`, `(0..=n).step_by(2)` | `for i in 0..=n step 2 { }` |
| `for i in range(n - 1, -1, -1)`, `(0..n).rev()`, `reversed(xs)` | `for i in rev 0..n { }`, `for x in rev xs { }` |
| `for i, x in enumerate(xs)`, `xs.iter().enumerate()` | `for i, x in xs { }` |
| `'outer: for ...` / `break 'outer` (Rust), `break outer` (Go, Java) | `outer: for ... { }` and `break outer;`, `continue outer;` |
| `loop { }` | `while true limit 1000 { ... break; }` |
| `s.push_str("a")`, `format!("{} {}", a, b)` | `append(mut s, "a")`, `var s: str[64] = ""; append(mut s, a, " ", b);` |
| `for line in text.splitlines()`, `for w in s.split()` | `for line in split(text, '\n') { }`, `for w in words(s) { }` |
| `s.split(",")`, `s.split()`, `", ".join(xs)` | `let parts: list[str[16]; 8] = split(s, ",");`, `words(s)`, `let line: str[64] = join(xs, ", ");` |
| the same on input that may not fit | `var parts: list[str[16]; 8] = []; let ok: bool = try_split(mut parts, s, ",");` |
| `s.count(",")`, `xs.count(x)` | `count(s, ',')`, `count(xs, x)` |
| `int(s)`, `s.parse::<i64>()` | `i64(trim(s))`, after checking `is_int(trim(s))`: bad text traps |
| `float(s)`, `s.parse::<f64>()` | `f64(trim(s))`, after checking `is_float(trim(s))`: digits and one `.`, no exponent |
| `f"{x:.2f}"`, `format!("{:.2}", x)`, `x.toFixed(2)` | `fixed(x, 2)`, in `print`/`append` or stored in a typed str |
| `f"{n:03}"`, `s.rjust(8)`, `s.ljust(8)`, `padStart` | `pad_left(n, 3, '0')`, `pad_left(s, 8, ' ')`, `pad_right(s, 8, ' ')` |
| `fn total(xs: &[i64])`, `fn f(s: &str)` | `fn total(xs: list[i64; _])`, `fn f(s: str[_])`: any capacity |
| `fn first<T>(xs: &[T]) -> T`, `def first(xs)` | `fn first<T>(xs: list[T; _]) -> T`: a type parameter; order with `compare(a, b) < 0`, not `<` |
| `xs.filter(f)`, `map(f, xs)`, `sort(key=f)`, a callback | a parameter `f: fn(T) -> bool`, passed by name: `count_if(xs, is_even)`; no closures or lambdas |
| `s[a:b]`, `s[a:]`, `&s[a..b]`, `s.strip()`, `s.upper()` | `s[a..b]`, `s[a..]` (read in place, not copied, where it is only read), `trim(s)`, `to_upper(s)` |
| a `str[256]` passed where `str[16]` is wanted | `str[16](s)` after checking `len(s) <= 16` |
| `x in xs`, `s.find("x")`, `s.startswith("x")` | `contains(xs, x)`, `index_of(s, "x")` (-1 if absent), `starts_with(s, "x")` |
| `xs.sort()`, `sorted(xs, reverse=True)` | `sort(mut xs);` then `reverse(mut xs);` |
| `xs.insert(i, v)`, `xs.pop(i)` | `insert(mut xs, i, v);`, `let v: T = remove(mut xs, i);` |
| `println!("{}", x)` | `print(x);` |
| `throw` / `raise` | return an enum, or `panic("message")` |
| `c ? a : b`, `a if c else b`, `if c { a } else { b }` as a value | `if c then a else b` |
| `case x if x > 0 =>`, `Some(v) if v > 0 =>` | `_ if x > 0 => { }`, `MaybeI64.Some(v) if v > 0 => { }` (guards) |
| `sys.stdin.read()`, `open(path).read()`, `input()` | `fn main(input: str[4096])`, run with `< path`; split it into lines |
| `import utils`, `#include "utils.h"`, `use crate::utils` | `import "utils.al";` before any declaration |
| `if x { }` with `x: i32` | `if x != 0 { }` |

## The standard library

Builtins that work on any type and capacity. Their names are reserved: call `gcd`, do not define it. For
what it leaves out (parsing into an enum instead of trapping, pseudo-random numbers, a map that keeps
the order its keys were added in), [examples/helpers.al](examples/helpers.al) has tested helpers to import or copy.

| Area | Functions |
|------|-----------|
| Math | `abs` `min` `max` `clamp(x, lo, hi)` `pow(x, n)` (integers, or floats: `pow(2.0, 0.5)`) `gcd` `lcm` `wrapping_add` `wrapping_sub` `wrapping_mul` `saturating_add` `saturating_sub` `saturating_mul` `sqrt` `floor` `ceil` `round` `trunc` `exp` `log` `log2` `log10` `sin` `cos` `tan` `asin` `acos` `atan` `atan2(y, x)` `is_nan` `is_finite` |
| Characters (`u8`, ASCII) | `is_digit` `is_alpha` `is_alnum` `is_space` `is_upper` `is_lower` `to_upper` `to_lower` (also on a whole str) |
| Text | `contains` `index_of` `count` `starts_with` `ends_with` `slice(s, from, to)` or `s[from..to]` `trim` `split(s, sep)` `words(s)` `try_split(mut parts, s, sep)` `try_words(mut parts, s)` `join(parts, sep)` `replace(s, from, to)` `is_int` `is_float` `fixed(x, decimals)` `pad_left(v, width, fill)` `pad_right(v, width, fill)`; UTF-8: `is_utf8` `char_count` `chars(s)` `push_char(mut s, c)` `slice_chars(s, from, to)` |
| Collections | `sort(mut xs)` `reverse(mut xs)` `insert(mut xs, i, v)` `remove(mut xs, i)`; `contains`, `index_of`, `count`, `slice` on lists and arrays; maps and sets: `m[k]`, `m[k] = v`, `get_or(m, k, d)`, `insert(mut s, x)`, `remove(mut m, k)`, `contains`, `len`, `clear` |

- `split`, `words`, `join` and `replace` take their type from the variable they are stored in, so make the
  call the whole right side of a typed `let`/`var`, assignment or `return`:
  `let parts: list[str[16]; 8] = split(line, ",");`. Too many or too long pieces trap (R0004), so when the
  text is not a constant and may not fit, `check` warns (W0307). `try_split(mut parts, s, sep)` and
  `try_words(mut parts, s)` fill the list instead and return false, leaving it empty, when it does not fit.
- `for p in split(s, sep) { }` and `for w in words(s) { }` go through the pieces one at a time instead:
  no list, and no trap however long or many they are. Each piece has the capacity of `s`, and so would
  `trim(p)` or `slice(p, ...)`: check `len(p)` and narrow it with `str[16](p)` first, then work on that.
  Use this for anything read from input.
- A separator or search pattern is a str or a character: `split(line, ',')`, `contains(s, "ab")`.
- `i64(s)` and `f64(s)` parse decimal text and trap on anything else; `trim` it and check `is_int` or
  `is_float` first. `f64` takes digits with at most one `.` and no exponent (`19.99`, `-0.5`, `3`), and
  converts up to 15 significant digits exactly.
- `fixed(x, 2)`, `pad_left(v, width, fill)` and `pad_right(...)` make text for `print` and `append`, or for
  a typed str: `append(mut s, "$", cents / 100, ".", pad_left(cents % 100, 2, '0'));`,
  `let cell: str[8] = pad_left(n, 6, ' ');`. Widths count bytes; longer text is kept whole.
- `index_of` returns -1 when nothing is found.
- `sort` orders every type: numbers, then strs by bytes (as `<` does), structs field by field, enums by variant. To
  sort records by a key, make the key the first field; for descending order, `reverse` afterwards.

```alang
struct Score {
    points: i64,
    name: str[32],
}

/// Parses "name:points" lines and ranks them, best first; lines that do not parse are skipped, and
/// the first 16 that do are kept.
tool fn rank(text: str[512]) -> list[Score; 16] {
    var scores: list[Score; 16] = [];
    for line in split(text, '\n') {
        var parts: list[str[32]; 2] = [];
        let ok: bool = try_split(mut parts, line, ':');
        if ok and len(parts) == 2 and is_int(trim(parts[1])) and len(scores) < cap(scores) {
            push(mut scores, Score(points: i64(trim(parts[1])), name: trim(parts[0])));
        }
    }
    sort(mut scores);
    reverse(mut scores);
    return scores;
}

fn main() {
    print(rank("ann: 7\nbob: 12\nbad line\ncy: 9"));
}

test "best first, bad lines skipped" {
    let r: list[Score; 16] = rank("ann: 7\nbob: 12\nbad line\ncy: 9");
    assert len(r) == 3;
    assert r[0].name == "bob";
}
```

## Patterns

**One function for every type**

```alang
// A type parameter takes any type, and a parameter of a function type a function, passed by name.
fn count_if<T>(xs: list[T; _], keep: fn(T) -> bool) -> i64 {
    var n: i64 = 0;
    for x in xs {
        if keep(x) {
            n += 1;
        }
    }
    return n;
}

fn largest<T>(xs: list[T; _]) -> T
    requires len(xs) > 0
{
    var best: T = xs[0];
    for x in xs {
        if compare(x, best) > 0 {
            best = x;
        }
    }
    return best;
}

fn is_even(x: i64) -> bool {
    return x % 2 == 0;
}

fn main() {
    let xs: list[i64; 8] = [3, 8, 1, 6];
    print(count_if(xs, is_even), " ", largest(xs), " ", largest(["b", "a"]));
}
```

**One function for every capacity**

```alang
// A `_` in a parameter's type takes any capacity: each call runs a copy made for its arguments.
fn total(xs: list[i64; _]) -> i64 {
    var sum: i64 = 0;
    for x in xs {
        sum += x;
    }
    return sum;
}

fn shout(s: mut str[_]) {
    s = to_upper(s);
    push(mut s, '!');    // traps when s is full: the capacity is the caller's variable's
}

fn main() {
    let few: list[i64; 4] = [1, 2, 3];
    let many: list[i64; 1000] = [10, 20];
    var name: str[16] = "ann";
    shout(mut name);
    print(total(few), " ", total(many), " ", name);
}
```

**Optional value**

```alang
enum MaybeI64 {
    None,
    Some(i64),
}

fn first_negative(xs: [i64; 4]) -> MaybeI64 {
    for x in xs {
        if x < 0 {
            return MaybeI64.Some(x);
        }
    }
    return MaybeI64.None;
}

fn main() {
    match first_negative([3, -1, 4, -5]) {
        MaybeI64.Some(v) => { print("found ", v); }
        MaybeI64.None => { print("none"); }
    }
}
```

**Replacing recursion with a work stack**

```alang
// Sum of a binary tree stored in arrays: node i has children left[i] and right[i] (-1 = none).
const N: i64 = 5;
const VALUE: [i64; 5] = [1, 2, 3, 4, 5];
const LEFT: [i64; 5] = [1, 3, -1, -1, -1];
const RIGHT: [i64; 5] = [2, 4, -1, -1, -1];

fn tree_sum() -> i64 {
    var stack: list[i64; N] = [0];
    var total: i64 = 0;
    while len(stack) > 0 limit N {
        let node: i64 = pop(mut stack);
        total += VALUE[node];
        if LEFT[node] >= 0 {
            push(mut stack, LEFT[node]);
        }
        if RIGHT[node] >= 0 {
            push(mut stack, RIGHT[node]);
        }
    }
    return total;
}

fn main() {
    print(tree_sum());
}
```

**Reading input**

```alang
// bin/alang run total.al < numbers.txt: one integer per line; anything else is reported, not trapped on.
fn main(input: str[65536]) {
    var total: i64 = 0;
    for line in split(input, '\n') {
        if len(line) > 32 {
            print("line too long: ", len(line), " bytes");
            continue;
        }
        let t: str[32] = trim(str[32](line));
        if is_int(t) {
            total += i64(t);
        } else if len(t) > 0 {
            print("not a number: ", t);
        }
    }
    print(total);
}
```

The loop builds no list, so the input can be as large as `fn main` accepts (up to 1 MiB); a
`let lines: list[str[512]; 8192] = split(input, '\n')` would need 4 MB of stack and trap on one long line.

**Splitting a program into files**

```
// lib/fmt.al
const WIDTH: i64 = 8;

fn pad(s: str[32]) -> str[32] { ... }

test "pad" { ... }    // bin/alang test lib/fmt.al runs it; report.al leaves it out
```

```
// report.al
import "lib/fmt.al";

fn main() {
    print(pad("42"), " points");
}
```

`bin/alang check report.al` checks both files; a diagnostic or trap in `lib/fmt.al` names that file (a
`"file"` key in JSON). There are no namespaces: every name in the program must be unique.

**Contracts document and check intent**

```alang
fn average(xs: list[f64; 32]) -> f64
    requires len(xs) > 0
    ensures result >= 0.0
{
    var sum: f64 = 0.0;
    for x in xs {
        sum += abs(x);
    }
    return sum / f64(len(xs));
}

fn main() {
    print(average([1.0, -2.0, 3.0]));
}
```

## Writing tools for agents

Mark a function `tool fn` and document it with `///`. Then:

```sh
bin/alang tools prog.al                        # JSON tool definitions (name, description, input_schema, input_examples)
bin/alang call prog.al word_counts '{"text": "a b a"}'
# {"ok": true, "result": [{"word": "a", "count": 2}, {"word": "b", "count": 1}], "stdout": "", "steps": 41}
```

The definitions are all the calling model knows about your tool, so give them something to say:

- A `///` line above a parameter, a struct field or an enum variant describes it in the schema.
- `requires` clauses are listed in the description, and simple ones such as
  `requires people > 0 and people <= 16` become schema bounds (`"minimum": 1, "maximum": 16`).
- Calls in your `test` blocks with literal arguments, such as `word_counts("a b a")`, become
  `input_examples`.

Before handing tools over, run `bin/alang fuzz prog.al`. It calls each tool with arguments at the limits
its schema allows and reports every trap with the arguments that caused it; for a word_counts that
pushed a 17th word into its list of 16, it prints:

```text
tool word_counts: 23 call(s), 1 trap(s)
trap[R0004]: push to a full list (capacity 16)
  ...
  = arguments: {"text": "a b c d e f g h i j k l m n o p q r s t u v w x … (256 bytes)"}
```

Each is input a caller can send: handle it (`try_split`, a `len` or `cap` check before a `push`), or rule
it out with `requires`, which the schema then shows too.

JSON mapping: numbers and bools as JSON numbers and booleans, `str[N]` as a string of at most N UTF-8
bytes (a non-ASCII character takes 2 to 4, so size N in bytes, not characters), arrays and lists as
arrays, tuples as arrays of fixed length, maps with str keys as objects (others as `[key, value]` pairs), sets as
arrays of distinct items, structs as objects (all fields required), enum variants as `"Name"` or
`{"Name": [payload...]}`.
Tools cannot take `mut` parameters. A trap inside a tool returns `{"ok": false, "error": {"code":
"R....", ...}}` instead of crashing the caller. JSON has no NaN or infinity, so a float result holding one
fails with `bad_result` (check for it in the tool); a `str` result must be UTF-8 text, so return raw
bytes as a `list[u8; N]`. Pass `-` instead of the JSON to read the arguments from stdin.

To hand the tools to an agent host, run `bin/alang serve prog.al`: an MCP server on stdin and stdout with
every `tool fn`, its result schema, and the same JSON per call. Restart it after editing the program.

Tools are safe to run minutes after you wrote them: no file, network, clock or random access, no heap,
bounded loops, no recursion, and a deterministic step budget.

## Determinism checklist

You get these for free; do not work around them:

- Same input, same output, same trap, on every run, machine, compiler and locale.
- No time, randomness, environment, threads or I/O other than `print` and the stdin of `fn main`. For a
  "random" value, take a seed as a tool argument and compute from it.
- Floats are bit-exact across the interpreter and the C backend, `sin`, `exp`, `log` and float `pow` included:
  ALang computes them itself rather than calling a math library, whose last bits differ.
- `print` shows floats with at most 6 decimals (`0.1 + 0.2` prints `0.3`); compare floats in tests with a
  tolerance, e.g. `assert abs(x - 0.3) < 0.000001;`.
