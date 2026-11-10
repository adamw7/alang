# Writing ALang: a guide for AI agents

ALang is built for you. It is small, explicit and strict, and every mistake comes back as a coded
diagnostic with a concrete fix. Read this page once; it covers everything you need. The full
rules are in [docs/SPEC.md](docs/SPEC.md), the grammar in [grammar/ALang.g4](grammar/ALang.g4),
every error code in [docs/ERRORS.md](docs/ERRORS.md).

## The loop

```sh
bin/alang check prog.al --json    # 1. diagnostics: code, line, column, message, hint
bin/alang test  prog.al --json    # 2. run every `test "..." { }` block
bin/alang run   prog.al           # 3. run fn main()
bin/alang explain E0215           #    explain any code
```

Fix the **first** diagnostic first, using its `hint`. Re-run `check` after every edit. Results are
deterministic: if a test fails once, it fails every time, identically.

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
        Found.Missing => { push(mut entries, Entry(word: word, count: 1)); }
    }
}

/// Counts how often each space-separated word occurs (at most 16 distinct words).
tool fn word_counts(text: str[256]) -> list[Entry; MAX_WORDS] {
    var entries: list[Entry; MAX_WORDS] = [];
    var word: str[16] = "";
    for c in text {
        if c == ' ' {
            if len(word) > 0 {
                add_word(mut entries, word);
                clear(mut word);
            }
        } else {
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
3. **No heap, no null, no pointers.** Fixed-capacity types: `str[64]`, `list[T; 16]`, `[T; 4]`. Capacity overflow traps.
4. **Structs use named arguments**: `Point(x: 1, y: 2)`. Never `Point { x: 1 }`.
5. **Enum variants are qualified with a dot**: `Color.Red`, `Shape.Circle(2.0)`. Never `Color::Red`.
6. **Logic uses words**: `and`, `or`, `not`. `&&`, `||`, `!x` are errors (`!=` is fine).
7. **No precedence guessing**: `* / %` bind tighter than `+ -`; everything else needs parentheses when mixed: `(a and b) or c`, `(flags & 4) != 0`, `(x << 2) + 1`. Comparisons do not chain: `0 < x and x < 10`.
8. **`while` needs a limit**: `while lo < hi limit 64 { }`. Exceeding it traps. Prefer `for i in 0..n` or `for x in xs` when you can.
9. **No recursion.** Use a loop with an explicit work list (`list[T; N]` as a stack or queue).
10. **`mut` is explicit on both sides**: `fn grow(xs: mut list[i32; 8])` and `grow(mut xs)`. Plain parameters are read-only copies.
11. **A call with a `mut` argument is its own statement** or the whole right side of `let`/assignment/`return`: `let top: i32 = pop(mut stack);` then use `top`.
12. **A `mut` variable cannot appear twice in one call**: `append(mut s, s)` is an error.
13. **No shadowing.** Pick a new name instead of redeclaring one in an inner block.
14. **`match` must be exhaustive.** Add `_ => { }` last when matching integers or strings.
15. **Functions with a return type return on every path.** Add a final `return` after loops.
16. **No methods.** `len(xs)`, not `xs.len()`; `push(mut xs, v)`, not `xs.push(v)`.
17. **Strings are byte buffers**: `s[i]` is a `u8`, `'a'` is a `u8` constant. Build text with `append(mut s, "x = ", x)`; add one character with `push(mut s, 'a')`.
18. **No exceptions.** Return an enum for recoverable failures; `panic("...")` or `assert` for bugs.

## Translating habits from other languages

| You might write | Write in ALang |
|-----------------|----------------|
| `let x = 5;` / `x := 5` | `let x: i64 = 5;` |
| `int`, `usize`, `String`, `Vec<T>` | `i32`/`i64`, `i64`, `str[N]`, `list[T; N]` |
| `Option<T>`, `null` | `enum MaybeT { None, Some(T) }` |
| `Point { x: 1, y: 2 }` | `Point(x: 1, y: 2)` |
| `Color::Red`, `Color.RED` | `Color.Red` |
| `a && !b \|\| c` | `(a and not b) or c` |
| `x as i64`, `(long) x` | `i64(x)` |
| `i++` | `i += 1;` |
| `for (i = 0; i < n; i++)` | `for i in 0..n { }` |
| `while (cond)` | `while cond limit 1000 { }` |
| `loop { }` | `while true limit 1000 { ... break; }` |
| `xs.len()`, `s.push_str("a")` | `len(xs)`, `append(mut s, "a")` |
| `format!("{} {}", a, b)` | `var s: str[64] = ""; append(mut s, a, " ", b);` |
| `println!("{}", x)` | `print(x);` |
| `throw` / `raise` | return an enum, or `panic("message")` |
| `fn f(v: &mut Vec<i32>)` | `fn f(v: mut list[i32; 16])`, called as `f(mut v)` |
| recursion | a loop plus a `list[T; N]` work stack |
| `if x { }` with `x: i32` | `if x != 0 { }` |

## Patterns

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
bin/alang tools prog.al                        # JSON tool definitions (name, description, input_schema)
bin/alang call prog.al word_counts '{"text": "a b a"}'
# {"ok": true, "result": [{"word": "a", "count": 2}, {"word": "b", "count": 1}], "stdout": ""}
```

JSON mapping: integers/floats/bools as JSON numbers/booleans, `str[N]` as a string,
arrays and lists as JSON arrays, structs as objects (all fields required), enum variants as
`"Name"` or `{"Name": [payload...]}`. Tools cannot take `mut` parameters. A trap inside a tool
returns `{"ok": false, "error": {"code": "R....", ...}}` instead of crashing the caller. JSON has no
NaN or infinity, so a float result holding one fails with `bad_result`: check for it in the tool. A
`str` result must be UTF-8 text for the same reason; return raw bytes as a `list[u8; N]`.
Pass `-` instead of the JSON to read the arguments from stdin.

Tools are safe to run even when you wrote them minutes ago: no file, network, clock or random
access, no heap, bounded loops, no recursion, and a deterministic step budget.

## Determinism checklist

You get these for free; do not try to work around them:

- Same input, same output, same trap — on every run, machine, compiler and locale.
- No time, randomness, environment, threads or I/O other than `print`. If a task needs a "random"
  value, take a seed as a tool argument and compute deterministically from it.
- Float results are bit-exact across the interpreter and the C backend: only `+ - * /`, `sqrt`,
  `floor`, `ceil`, `abs`, `min`, `max` and conversions exist (no `sin`/`exp`/`pow`).
- `print` of floats shows 6 decimals at most (`0.1 + 0.2` prints `0.3`); compare floats in tests with
  a tolerance, e.g. `assert abs(x - 0.3) < 0.000001;`.
