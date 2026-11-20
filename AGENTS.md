# Writing ALang: a guide for AI agents

ALang is small, explicit and strict; every mistake comes back as a coded diagnostic with a fix. Full
rules: [docs/SPEC.md](docs/SPEC.md); every code: [docs/ERRORS.md](docs/ERRORS.md).

## The loop

```sh
bin/alang check prog.al --json    # 1. diagnostics: code, line, column, message, hint
bin/alang test  prog.al --json    # 2. run every `test "..." { }` block (`test "..." traps R0006 { }` must trap)
bin/alang fuzz  prog.al           # 3. call each tool fn, and a main that reads input, at its limits
bin/alang run   prog.al           # 4. run fn main()
bin/alang run   prog.al < in.txt  #    a fn main(input: str[N]) gets all of stdin
bin/alang explain E0215           #    explain any code
bin/alang deps  prog.al --lock    #    pin the packages alang.json declares (import "@name/file.al")
bin/alang fix   prog.al --write   #    apply every `= fix:` (`"fix"` in JSON): &&, x++, xs.len(), a missing ;, mut or type
bin/alang fmt   prog.al --write   #    lay the file out the one way
bin/alang test  prog.al --filter X #    run only the tests whose name contains X
bin/alang check prog.al --memory   #    per-function worst-case stack table
bin/alang build prog.al -o prog.c  #    compile to heap-free C99 (needs a C compiler to run it)
bin/alang debug prog.al --break 12 --json < /dev/null  # every stop at line 12, with the variables in scope
```

Fix the **first** diagnostic first, using its `hint`, and re-run `check` after every edit; `alang fix
--write` applies every `= fix:`. Results are deterministic. `fuzz` tries inputs your tests did not and
reports each trap with its arguments: handle that input, or rule it out with `requires`.

To see why a value is wrong, debug instead of adding `print`s: `--break LINE` prints every stop at that
line with the variables in scope (`--break fn_name`, `--break "12 if i == 3"`, `--test NAME`,
`--call TOOL '{...}'` also work, and a trap always stops). Pipe commands in to drive it: `step`, `next`,
`finish`, `continue`, `where`, `frame N`, `locals`, `print EXPR`, `help`.

No `bin/alang`? `curl -fsSL https://raw.githubusercontent.com/adamw7/alang/main/install.sh | sh` (Linux,
macOS), or unpack a [release](https://github.com/adamw7/alang/releases/latest) archive (needs Java 21+).

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
4. **Structs use named arguments**: `Point(x: 1, y: 2)`. Never `Point { x: 1 }`. A field may have a constant
   default, `retries: i64 = 3`, which a value leaving it out takes (`Config()` when every field has one);
   `Point(..p, y: 0)` copies the other fields from the variable `p`.
5. **Enum variants are qualified with a dot**: `Color.Red`, `Shape.Circle(2.0)`. Never `Color::Red`.
6. **Logic uses words**: `and`, `or`, `not`. `&&`, `||`, `!x` are errors (`!=` is fine).
7. **No precedence guessing**: `* / %` bind tighter than `+ -`; everything else needs parentheses when mixed: `(a and b) or c`, `(flags & 4) != 0`, `(x << 2) + 1`. Comparisons do not chain: `0 < x and x < 10`.
8. **`while` needs a limit**: `while lo < hi limit 64 { }`, `while let Option.Some(x) = next(q) limit 64 { }`.
   Exceeding it traps. Prefer `for i in 0..n` or `for x in xs` when you can:
   ranges also go `0..=n`, `step 2` and `rev 0..n` (backwards), and `for i, x in xs` gives each index.
9. **No recursion.** Use a loop with an explicit work list (`list[T; N]` as a stack or queue).
10. **`mut` is explicit on both sides**: `fn grow(xs: mut list[i32; 8])` and `grow(mut xs)`. Plain parameters are read-only copies.
    So is every variable: `var it: Item = items[0]; it.qty += 1;` changes the copy, not the list (warning
    W0305); write `items[0].qty += 1;`.
11. **A call with a `mut` argument is its own statement** or the whole right side of `let`/assignment/`return` (or of `while let P =`): `let top: i32 = pop(mut stack);` then use `top`.
12. **A `mut` variable cannot appear twice in one call**: `append(mut s, s)` and `swap(mut xs[i], mut xs[j])` are errors (write `swap(mut xs, i, j)`). Numbers passed without `mut` are copied first, so `remove(mut xs, len(xs) - 1)` is fine.
13. **No shadowing.** Pick a new name instead of redeclaring one in an inner block.
14. **`match` must be exhaustive.** Add `_ => { }` last when matching integers or strings. Patterns nest:
    `Option.Some(0)`, `(Color.Red, true)`, `Point(x: 0, y: py)`, `1..=9`, `'a'..='z'`, `6 | 7`, and a plain
    name, which binds a copy (there are no float patterns). An arm may have a guard,
    `Option.Some(v) if v > 0 => { }`; guarded arms do not count toward exhaustiveness. As a value:
    `let s: str[8] = match n { 0 => "zero", _ => "many" };`. One case only: `if let Option.Some(v) = m { }`, or
    `let Option.Some(v): Option<i64> = m else { return 0; };`, whose block must leave (return, break, continue).
15. **Functions with a return type return on every path.** Add a final `return` after loops.
16. **No methods.** `len(xs)`, not `xs.len()`; `push(mut xs, v)`, not `xs.push(v)`; `trim(s)`, not `s.trim()`.
17. **Strings are byte buffers**: `s[i]` is a `u8`, `'a'` is a `u8` constant. Build text with `append(mut s, "x = ", x)`; add one character with `push(mut s, 'a')`. Two strs compare with `==` and order with `<`, byte by byte. For characters beyond ASCII, `char_count(s)` counts
    code points, `for c in chars(s) { }` goes through them (each a `u32`), and `slice_chars` cuts between them.
18. **No exceptions, no null.** Return `Option<T>` (`Option.Some(x)`, `Option.None`) for a value that may be
    absent and `Result<T, E>` (`Result.Ok(x)`, `Result.Err(why)`) for one that can fail; both are built in.
    `let n: i64 = parse_i64(s)?;` takes the value out, or returns the `Option.None` (or `Result.Err`) from a function
    that returns an `Option` (a `Result`); `unwrap_or(o, 0)`, `is_some(o)`, `is_ok(r)` read one without a `match`.
    `panic("...")` or `assert` for bugs. Structs and enums of your own take type parameters too:
    `struct Pair<A, B> { first: A, second: B }`, used as `Pair<i64, str[8]>`.
19. **Input arrives whole; nothing else is read.** `fn main(input: str[4096])` gets all of stdin as bytes
    (`bin/alang run prog.al < data.txt`; more than 4096 bytes traps), a `tool fn` gets JSON. No files.
    Go through it with `for line in split(input, '\n') { }`: one line at a time, never a trap on a long one.
    Split a line into a list with `try_split(mut fields, line, ',')`: false, not a trap, when it does not fit.
20. **`import "lib/text.al";` at the top of a file** adds that file's declarations, by their plain names.
    The path is relative to the importing file, with `/`. Its `fn main`, tests and tools stay its own.
    `import "lib/text.al" as text;` keeps them apart instead: `text.pad(s)`, `text.WIDTH`, `text.Cell(...)`.

## Translating habits from other languages


| You might write | Write in ALang |
|-----------------|----------------|
| `let x = 5;` / `x := 5` | `let x: i64 = 5;` |
| `int`, `usize`, `String`, `Vec<T>` | `i32`/`i64`, `i64`, `str[N]`, `list[T; N]` |
| `dict`, `HashMap<K, V>`, `set()`, `HashSet<T>` | `map[str[16], i64; 64]`, `set[i64; 32]`: a capacity, keys always in order; `var m: map[str[8], i64; 16] = [];` then `m[k] = v;` |
| `d.get(k, 0)`, `d[k] = d.get(k, 0) + 1`, `for k, v in d.items()` | `get_or(m, k, 0)`, `m[k] = get_or(m, k, 0) + 1;`, `for k, v in m { }` |
| `list(d.keys())`, `d.values()`, `m.keys().collect()` | `keys(m)`, `values(m)`: lists of the map's capacity, in key order |
| `list(Color)`, `Color::iter()`, `Color.values()` | `for c in variants(Color) { }`: an array of every variant (of an enum whose variants carry no values) |
| `while let Some(x) = it.next()`, `while (m = re.exec(s))` | `while let Option.Some(x) = next(mut it) limit 1000 { }`: the failing test is not an iteration |
| `return a, b`, `(a, b) = f()`, `let (q, r) = divmod(a, b)` | `return (a, b);` with `-> (i64, i64)`, then `let (q, r): (i64, i64) = divmod(a, b);` and `p.0`, `p.1` |
| `for i in range(0, n + 1, 2)`, `(0..=n).step_by(2)` | `for i in 0..=n step 2 { }` |
| `for i in range(n - 1, -1, -1)`, `(0..n).rev()`, `reversed(xs)` | `for i in rev 0..n { }`, `for x in rev xs { }` |
| `for i, x in enumerate(xs)`, `xs.iter().enumerate()` | `for i, x in xs { }` |
| `for k, v in pairs`, `for (a, b) in pairs`, `for &(a, b) in pairs.iter()` | `for (a, b) in pairs { }`, and `for i, (a, b) in pairs { }` with each index |
| `'outer: for ...` / `break 'outer` (Rust), `break outer` (Go, Java) | `outer: for ... { }` and `break outer;`, `continue outer;` |
| `loop { }` | `while true limit 1000 { ... break; }` |
| `s.push_str("a")`, `format!("{} {}", a, b)` | `append(mut s, "a")`, `var s: str[64] = ""; append(mut s, a, " ", b);` |
| `f"x = {x}"`, `` `x = ${x}` `` | `print("x = ", x);`, `append(mut s, "x = ", x);`: no format or template strings |
| `let sep: u8 = ",";` | `let sep: u8 = ',';`: a character is one byte in single quotes; a str takes double quotes |
| `for line in text.splitlines()`, `for w in s.split()` | `for line in split(text, '\n') { }`, `for w in words(s) { }` |
| `s.split(",")`, `s.split()`, `", ".join(xs)` | `let parts: list[str[16]; 8] = split(s, ",");`, `words(s)`, `let line: str[64] = join(xs, ", ");` |
| the same on input that may not fit | `var parts: list[str[16]; 8] = []; let ok: bool = try_split(mut parts, s, ",");` |
| `s.count(",")`, `xs.count(x)` | `count(s, ',')`, `count(xs, x)` |
| `int(s)`, `s.parse::<i64>()` | `parse_i64(trim(s))`: `Option.Some(n)`, or `Option.None` for bad text; or `i64(trim(s))` after checking `is_int(trim(s))`, as bad text traps |
| `int(s, 16)`, `i64::from_str_radix(s, 2)` | `parse_i64(s, 16)`, `parse_i64(s, 2)`: digits only, no `0x` prefix |
| `str(n)`, `n.to_string()`, `String.valueOf(b)` | `let s: str[24] = str[24](n);`: the text of a number or bool, as `print` writes it |
| `hex(n)`, `format!("{:x}", n)`, `n.toString(2)`, `f"{b:02x}"` | `to_base(n, 16)`, `to_base(n, 2)`, `pad_left(to_base(b, 16), 2, '0')`, in `print`/`append` or a typed str |
| `x.count_ones()`, `Integer.bitCount(x)`, `x.leading_zeros()`, `x.rotate_left(3)` | `count_ones(x)`, `leading_zeros(x)`, `trailing_zeros(x)`, `rotl(x, 3)`, `rotr(x, 3)` |
| `hash(s)`, `s.hashCode()` | `hash(s)`: 64-bit FNV-1a of the bytes, the same on every run |
| `float(s)`, `s.parse::<f64>()` | `parse_f64(trim(s))`, or `f64(trim(s))` after checking `is_float(trim(s))`: an optional sign, digits and at most one `.` (`3.`, `.25` count), and an optional exponent (`1e5`, `2.5E-3`) |
| `a % m` in Python (never negative), `a.rem_euclid(m)`, `Math.floorMod(a, m)` | `rem_euclid(a, m)`: `rem_euclid(-1, 5)` is 4, where `-1 % 5` is -1; `a // m` is `div_euclid(a, m)` |
| `(a * b) % m` with big numbers, `pow(a, b, m)` | `mul_mod(a, b, m)`: exact however large `a * b` is (for `pow(a, b, m)`, square and multiply with it) |
| `a.checked_add(b)`, `Math.addExact` in a `try` | `checked_add(a, b)`, `checked_sub`, `checked_mul`: an `Option`, `Option.None` when it does not fit |
| `f"{x:.2f}"`, `format!("{:.2}", x)`, `x.toFixed(2)` | `fixed(x, 2)`, in `print`/`append` or stored in a typed str |
| `f"{n:03}"`, `s.rjust(8)`, `s.ljust(8)`, `padStart` | `pad_left(n, 3, '0')`, `pad_left(s, 8, ' ')`, `pad_right(s, 8, ' ')` |
| `def f(x, y=2)`, `function f(x, y = 2)` | `fn f(x: i64, y: i64 = 2)`: a constant default, after the parameters without one |
| `fn first<T>(xs: &[T]) -> T`, `def first(xs)` | `fn first<T>(xs: list[T; _]) -> T`: a type parameter; order with `compare(a, b) < 0`, not `<` |
| `any(f(x) for x in xs)`, `xs.iter().all(f)`, `xs.filter(f).count()`, `xs.iter().position(f)` | `any(xs, f)`, `all(xs, f)`, `count_if(xs, f)`, `find_index(xs, f)` (-1 if none), with `fn f(x: T) -> bool` passed by name |
| `xs = [x for x in xs if f(x)]`, `xs.retain(f)` | `retain(mut xs, f);` |
| `map(f, xs)`, a callback of your own | a parameter `f: fn(T) -> U`, passed by name: `fn apply(xs: list[i64; _], f: fn(i64) -> i64)`; no closures or lambdas |
| `s[a:b]`, `s[a:]`, `&s[a..b]`, `s.strip()`, `s.upper()` | `s[a..b]`, `s[a..]` (read in place, not copied, where it is only read), `trim(s)`, `to_upper(s)` |
| `s.removeprefix("v")`, `s.strip_suffix(".txt")` | `strip_prefix(s, 'v')`, `strip_suffix(s, ".txt")`: `s` unchanged when it does not start (end) with it |
| `k, _, v = s.partition("=")`, `s.split_once('=')` | `split_once(s, '=')`: `Option.Some((before, after))`, or `Option.None` |
| `"-" * 20`, `"ab".repeat(3)` | `repeat('-', 20)`, `repeat("ab", 3)`, in `print`/`append` or a typed str |
| `x in xs`, `s.find("x")`, `s.startswith("x")` | `contains(xs, x)`, `index_of(s, "x")` (-1 if absent), `starts_with(s, "x")` |
| `s.rfind("x")`, `xs.lastIndexOf(x)` | `last_index_of(s, "x")`, `last_index_of(xs, x)` (-1 if absent) |
| `sum(xs)`, `min(xs)`, `max(xs)`, `xs.iter().sum()` | `sum(xs)`, `min(xs)`, `max(xs)` on an array or list of numbers (`min`/`max` trap on an empty one) |
| `sorted(set(xs))`, `xs.dedup()`, `uniq` | `sort(mut xs);` then `dedup(mut xs);` |
| `bisect_left(xs, x)`, `xs.binary_search(&x)` | `binary_search(xs, x)` on a sorted list: the position of an equal element, or -1 |
| `xs.sort()`, `sorted(xs, reverse=True)` | `sort(mut xs);` then `reverse(mut xs);` |
| `xs.sort(key=f)`, `xs.sort_by(cmp)`, `Collections.sort(xs, cmp)` | `sort_by(mut xs, before)`, with `fn before(a: T, b: T) -> bool` true when `a` must come first; stable |
| `xs.insert(i, v)`, `xs.pop(i)` | `insert(mut xs, i, v);`, `let v: T = remove(mut xs, i);` |
| `class`, `impl`, `trait`, `interface` | `struct Shape { ... }` and functions that take it: `fn area(s: Shape) -> f64` |
| `pub fn`, `export function`, `public` | `fn`: every top-level declaration is visible to the files that import it |
| `const N = 10;` inside a function | `let n: i64 = 10;`; `const` is for the top level of the file |
| `c ? a : b`, `a if c else b`, `if c { a } else { b }` as a value | `if c then a else b` |
| `x?`, `try!(x)`, `if err != nil { return err }` | `let v: i64 = f(x)?;` in a function returning `Option`/`Result`; the error type must hold the callee's |
| `let Some(v) = o else { return 0; };`, `guard let v = o else { return 0 }` | `let Option.Some(v): Option<i64> = o else { return 0; };` |
| `o.unwrap_or(0)`, `o ?? 0`, `o.getOrElse(0)`, `o.is_some()`, `r.is_err()` | `unwrap_or(o, 0)`, `is_some(o)`, `is_none(o)`, `is_ok(r)`, `is_err(r)` |
| `a, b = b, a`, `std::mem::swap(&mut a, &mut b)`, `xs.swap(i, j)` | `swap(mut a, mut b);`, `swap(mut xs, i, j);` |
| `xs.pop(0)`, `q.popleft()`, `xs.shift()`, `VecDeque::pop_front` | `let x: T = pop_front(mut xs);` (and `push(mut xs, x)` at the back) |
| `min(xs, key=f)`, `xs.iter().max_by_key(f)`, `minBy` | `min_by(xs, before)`, `max_by(xs, before)` with `fn before(a: T, b: T) -> bool` |
| `a \| b`, `a & b`, `a - b` on sets, `a.union(&b)` | `union(a, b)`, `intersection(a, b)`, `difference(a, b)` |
| `assert_eq!(a, b)`, `assertEqual(a, b)` | `assert_eq(a, b);` or `assert a == b;`: both show the two values when they differ |
| `let Point { x, y } = p;`, `const { x, y } = p` | `let Point(x: px, y: py): Point = p;` |
| `Point { y: 0, ..p }`, `{...p, y: 0}`, `dataclasses.replace(p, y=0)` | `Point(..p, y: 0)`, with `p` a variable |
| `@dataclass` field `= 3`, `#[derive(Default)]`, `Config::default()` | `struct Config { retries: i64 = 3, }` then `Config()` or `Config(retries: 5)` |

## The standard library

Builtins work on any type and capacity; their names are reserved. [examples/helpers.al](examples/helpers.al)
adds tested helpers (pseudo-random numbers, an insertion-ordered map).

| Area | Functions |
|------|-----------|
| Math | `abs` `min(a, b)` `max(a, b)` `min(xs)` `max(xs)` `sum(xs)` `clamp(x, lo, hi)` `pow(x, n)` (integers, or floats: `pow(2.0, 0.5)`) `gcd` `lcm` `wrapping_add` `wrapping_sub` `wrapping_mul` `saturating_add` `saturating_sub` `saturating_mul` `checked_add` `checked_sub` `checked_mul` (an `Option`) `rem_euclid` `div_euclid` `mul_mod(a, b, m)` `count_ones` `leading_zeros` `trailing_zeros` `rotl(x, n)` `rotr(x, n)` `sqrt` `floor` `ceil` `round` `trunc` `exp` `log` `log2` `log10` `sin` `cos` `tan` `asin` `acos` `atan` `atan2(y, x)` `is_nan` `is_finite` |
| Characters (`u8`, ASCII) | `is_digit` `is_alpha` `is_alnum` `is_space` `is_upper` `is_lower` `to_upper` `to_lower` (also on a whole str) |
| Text | `contains` `index_of` `last_index_of` `count` `starts_with` `ends_with` `slice(s, from, to)` or `s[from..to]` `trim` `strip_prefix` `strip_suffix` `split(s, sep)` `split_once(s, sep)` `words(s)` `try_split(mut parts, s, sep)` `try_words(mut parts, s)` `join(parts, sep)` `replace(s, from, to)` `repeat(s, n)` `is_int` `is_float` `parse_i64(s)` `parse_i64(s, base)` `parse_f64` (an `Option`) `str[N](n)` `fixed(x, decimals)` `pad_left(v, width, fill)` `pad_right(v, width, fill)` `to_base(n, base)` `hash(s)`; UTF-8: `is_utf8` `char_count` `chars(s)` `push_char(mut s, c)` `slice_chars(s, from, to)` |
| Option, Result | `unwrap_or(o, d)` `is_some` `is_none` `is_ok` `is_err`; `e?`; `assert_eq(a, b);` |
| Collections | `sort(mut xs)` `sort_by(mut xs, before)` `reverse(mut xs)` `dedup(mut xs)` `binary_search(xs, x)` `insert(mut xs, i, v)` `remove(mut xs, i)` `pop_front(mut xs)` `swap(mut xs, i, j)` `swap(mut a, mut b)` `retain(mut xs, keep)`; `any(xs, f)` `all(xs, f)` `count_if(xs, f)` `find_index(xs, f)` `min_by(xs, before)` `max_by(xs, before)` with a function passed by name; `contains`, `index_of`, `last_index_of`, `count`, `slice` on lists and arrays; maps and sets: `m[k]`, `m[k] = v`, `get_or(m, k, d)`, `keys(m)`, `values(m)`, `insert(mut s, x)`, `remove(mut m, k)`, `contains`, `len`, `clear`, `union(a, b)`, `intersection(a, b)`, `difference(a, b)`; enums: `variants(E)` |

- `split`, `words`, `join`, `replace` take their type from the typed `let`/`var`/assignment/`return` they
  are the whole right side of: `let parts: list[str[16]; 8] = split(line, ",");`. Pieces that do not fit
  trap (R0004; `check` warns W0307), so for input use `try_split`/`try_words` (false, list left empty) or
  iterate: `for p in split(s, sep) { }` builds no list and never traps; each piece has the capacity of
  `s`, so check `len(p)` and narrow with `str[16](p)`.
- A separator or pattern is a str or a character. `index_of` and friends return -1 when absent.
- `parse_i64`/`parse_f64` give an `Option`; `i64(s)`/`f64(s)` trap on bad text, so check `is_int`/`is_float`
  first, after `trim`. Floats accept `19.99`, `-0.5`, `.25`, `1e5`, `2.5E-3`.
- `fixed(x, 2)`, `pad_left(v, width, fill)`, `pad_right` make text for `print`, `append` or a typed str;
  widths count bytes.
- `sort` orders every type: numbers, strs by bytes, structs field by field (put the key first), enums by
  variant; `reverse` for descending, `sort_by(mut xs, before)` for any other order (stable).
- Functions passed by name must have exactly the wanted type: `fn adult(e: Entry) -> bool`.
- `x % m` keeps the sign of `x`; `rem_euclid` never is negative. `checked_*` return `Option.None` on overflow.

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
fn first_where<T>(xs: list[T; _], keep: fn(T) -> bool, otherwise: T) -> T {
    for x in xs {
        if keep(x) {
            return x;
        }
    }
    return otherwise;
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
    print(first_where(xs, is_even, 0), " ", largest(xs), " ", largest(["b", "a"]), " ", count_if(xs, is_even));
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

**Optional values and errors**

```alang
// Option<T> and Result<T, E> are built in; a value takes its type from where it goes.
fn first_negative(xs: [i64; 4]) -> Option<i64> {
    for x in xs {
        if x < 0 {
            return Option.Some(x);
        }
    }
    return Option.None;
}

fn parse_age(s: str[_]) -> Result<u8, str[32]> {
    let t: str[32] = str[32](trim(s));
    if not is_int(t) {
        return Result.Err("not a number");
    }
    let n: i64 = i64(t);
    if n < 0 or n > 150 {
        return Result.Err("out of range");
    }
    return Result.Ok(u8(n));
}

fn main() {
    match first_negative([3, -1, 4, -5]) {
        Option.Some(v) => { print("found ", v); }
        Option.None => { print("none"); }
    }
    if let Result.Ok(age) = parse_age(" 42 ") {
        print("age ", age);
    }
    print(parse_age("x"));
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

The loop builds no list, so the input can be as large as `fn main` accepts (up to 1 MiB).

**Several files**: `bin/alang check report.al` checks it and its imports, and a diagnostic or trap in an
imported file names that file. Names must be unique across them, unless imported `as name`.

**Packages**: a directory of `.al` files declared in `alang.json` beside the program (or above it) is
imported by name, `import "@text/fmt.al" as text;`, and pinned by the SHA-256 of its files:

```json
{"dependencies": {"text": {"path": "vendor/text", "sha256": "..."}}}
```

Nothing is downloaded: vendor the package in its `path` (a copy or a git submodule), then
`bin/alang deps prog.al --lock` writes its `sha256`. An edited package fails `check` with E0706 until it is
restored or locked again. Details: [SPEC.md §3.2](docs/SPEC.md#32-packages).

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

test "an empty list breaks the contract" traps R0006 {
    average([]);
}
```


## Writing tools for agents

Mark a function `tool fn` and document it with `///`. Then:

```sh
bin/alang tools prog.al                        # JSON tool definitions (name, description, input_schema, input_examples)
bin/alang call prog.al word_counts '{"text": "a b a"}'
# {"ok": true, "result": [{"word": "a", "count": 2}, {"word": "b", "count": 1}], "stdout": "", "steps": 45}
```

The definitions are all the calling model knows, so give them something to say: `///` on parameters,
struct fields and enum variants describes them; `requires` clauses are listed, and simple bounds such as
`requires people > 0 and people <= 16` become `"minimum": 1, "maximum": 16`; test calls with literal
arguments become `input_examples`; a parameter with a constant default (`max_words: i64 = 16`, after the
others) is optional.

Before handing tools over, run `bin/alang fuzz prog.al`; for a word_counts that pushed a 17th word into
its list of 16, it prints:

```text
tool word_counts: 23 call(s), 1 trap(s)
trap[R0004]: push to a full list (capacity 16)
  ...
  = arguments: {"text": "a b c d e f g h i j k l m n o p q r s t u v w x … (256 bytes)"}
```

Handle each such input (`try_split`, a `len`/`cap` check before `push`) or rule it out with `requires`.

JSON mapping: numbers, bools; `str[N]` as a string of at most N UTF-8 *bytes*; arrays, lists and tuples as
arrays; maps with str keys as objects (others as `[key, value]` pairs); sets as arrays; structs as objects
(all fields required); enum variants as `"Name"` or `{"Name": [payload...]}`. Tools cannot take `mut`
parameters. A trap returns `{"ok": false, "error": {"code": "R....", ...}}`. A float result holding NaN or
infinity fails with `bad_result`; return raw bytes as `list[u8; N]`, not `str`.

`bin/alang serve prog.al [--watch]` serves every tool over MCP on stdio (`--http 8080 --token-file token.txt`
for HTTP at `/mcp`). Tools have no file, network, clock or random access, and run within a deterministic
step budget (`steps`: one per statement plus about one per 128 bytes touched; the default 100 million is a
few seconds), backed by `--timeout` (60 s).

## Determinism checklist

- Same input, same output, same trap, on every run, machine, compiler and locale.
- No time, randomness, environment, threads or I/O but `print` and stdin. For "random", take a seed.
- Floats are bit-exact across the interpreter and the C backend, `sin`, `exp` and `pow` included.
- `print` shows floats with at most 6 decimals (`0.1 + 0.2` prints `0.3`); compare floats in tests with a
  tolerance, e.g. `assert abs(x - 0.3) < 0.000001;`.
