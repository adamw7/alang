// Library functions other languages have built in and ALang leaves out, each written once and tested.
// There are no imports or generics, so copy the ones you need into your program and change the
// capacities to fit your data: TEXT and ITEMS below, or the types themselves.
//
// A read-only `str[N]` or `list[T; N]` parameter also accepts a value of smaller capacity, so these
// helpers take any text up to TEXT bytes and any list up to ITEMS items.

const TEXT: i64 = 64;
const ITEMS: i64 = 16;
const I64_MIN: i64 = -9223372036854775807 - 1;

// ------------------------------------------------------------------ text

/// Whether `a` sorts before `b`, byte by byte: the order of C's strcmp, and of `<` on ASCII strings in
/// Python or Rust. ALang's `<` works only on numbers.
fn str_less(a: str[TEXT], b: str[TEXT]) -> bool {
    for i in 0..min(len(a), len(b)) {
        if a[i] != b[i] {
            return a[i] < b[i];
        }
    }
    return len(a) < len(b);
}

fn starts_with(s: str[TEXT], prefix: str[TEXT]) -> bool {
    if len(prefix) > len(s) {
        return false;
    }
    for i in 0..len(prefix) {
        if s[i] != prefix[i] {
            return false;
        }
    }
    return true;
}

fn ends_with(s: str[TEXT], suffix: str[TEXT]) -> bool {
    if len(suffix) > len(s) {
        return false;
    }
    let start: i64 = len(s) - len(suffix);
    for i in 0..len(suffix) {
        if s[start + i] != suffix[i] {
            return false;
        }
    }
    return true;
}

/// The index of the first occurrence of `part` in `s`, or -1. An empty `part` is found at 0.
fn index_of(s: str[TEXT], part: str[TEXT]) -> i64 {
    let stop: i64 = len(s) - len(part) + 1;
    for start in 0..stop {
        var found: bool = true;
        for i in 0..len(part) {
            if s[start + i] != part[i] {
                found = false;
                break;
            }
        }
        if found {
            return start;
        }
    }
    return -1;
}

/// The bytes of `s` from `start` up to, not including, `end`: Python's s[start:end].
fn substring(s: str[TEXT], start: i64, end: i64) -> str[TEXT]
    requires 0 <= start and start <= end and end <= len(s)
{
    var out: str[TEXT] = "";
    for i in start..end {
        push(mut out, s[i]);
    }
    return out;
}

fn is_space(c: u8) -> bool {
    return c == ' ' or c == '\t' or c == '\n' or c == '\r';
}

fn is_digit(c: u8) -> bool {
    return '0' <= c and c <= '9';
}

fn is_letter(c: u8) -> bool {
    return ('a' <= c and c <= 'z') or ('A' <= c and c <= 'Z');
}

/// `s` without spaces, tabs and line breaks at either end.
fn trim(s: str[TEXT]) -> str[TEXT] {
    var start: i64 = 0;
    while start < len(s) and is_space(s[start]) limit TEXT {
        start += 1;
    }
    var end: i64 = len(s);
    while end > start and is_space(s[end - 1]) limit TEXT {
        end -= 1;
    }
    return substring(s, start, end);
}

/// ASCII letters in upper case; every other byte unchanged.
fn to_upper(s: str[TEXT]) -> str[TEXT] {
    var out: str[TEXT] = "";
    for c in s {
        if 'a' <= c and c <= 'z' {
            push(mut out, c - 32);
        } else {
            push(mut out, c);
        }
    }
    return out;
}

/// ASCII letters in lower case; every other byte unchanged.
fn to_lower(s: str[TEXT]) -> str[TEXT] {
    var out: str[TEXT] = "";
    for c in s {
        if 'A' <= c and c <= 'Z' {
            push(mut out, c + 32);
        } else {
            push(mut out, c);
        }
    }
    return out;
}

/// The parts of `s` between the `sep` bytes. Empty parts are kept, as with Python's s.split(","):
/// "a,,b" gives ["a", "", "b"], and "" gives [""]. Traps (R0004) past ITEMS parts.
fn split(s: str[TEXT], sep: u8) -> list[str[TEXT]; ITEMS] {
    var parts: list[str[TEXT]; ITEMS] = [];
    var part: str[TEXT] = "";
    for c in s {
        if c == sep {
            push(mut parts, part);
            clear(mut part);
        } else {
            push(mut part, c);
        }
    }
    push(mut parts, part);
    return parts;
}

/// The words of `s`: the runs of bytes between spaces, tabs and line breaks, never empty. Python's
/// s.split() without an argument.
fn words(s: str[TEXT]) -> list[str[TEXT]; ITEMS] {
    var out: list[str[TEXT]; ITEMS] = [];
    var word: str[TEXT] = "";
    for c in s {
        if is_space(c) {
            if len(word) > 0 {
                push(mut out, word);
                clear(mut word);
            }
        } else {
            push(mut word, c);
        }
    }
    if len(word) > 0 {
        push(mut out, word);
    }
    return out;
}

/// The parts with `sep` between them. Traps (R0004) when the result is longer than TEXT bytes.
fn join(parts: list[str[TEXT]; ITEMS], sep: str[TEXT]) -> str[TEXT] {
    var out: str[TEXT] = "";
    for i in 0..len(parts) {
        if i > 0 {
            append(mut out, sep);
        }
        append(mut out, parts[i]);
    }
    return out;
}

/// `s` right-aligned in `width` bytes, filled on the left: pad_left("42", 5, '0') is "00042".
fn pad_left(s: str[TEXT], width: i64, fill: u8) -> str[TEXT]
    requires width <= TEXT
{
    var out: str[TEXT] = "";
    for i in len(s)..width {
        push(mut out, fill);
    }
    append(mut out, s);
    return out;
}

// ------------------------------------------------------------------ numbers

enum ParsedI64 {
    Ok(i64),
    Bad,
}

/// A decimal integer with an optional sign, as strict as Rust's `parse::<i64>()`: no spaces, no other
/// characters, nothing outside the i64 range. To turn a number into text, use append(mut s, n).
fn parse_i64(s: str[TEXT]) -> ParsedI64 {
    var first: i64 = 0;
    var negative: bool = false;
    if len(s) > 0 and (s[0] == '-' or s[0] == '+') {
        negative = s[0] == '-';
        first = 1;
    }
    if first == len(s) {
        return ParsedI64.Bad;
    }
    // Build the value below zero: i64 reaches one further below zero than above it.
    var n: i64 = 0;
    for i in first..len(s) {
        if not is_digit(s[i]) {
            return ParsedI64.Bad;
        }
        let digit: i64 = i64(s[i] - '0');
        if n < (I64_MIN + digit) / 10 {
            return ParsedI64.Bad;
        }
        n = n * 10 - digit;
    }
    if negative {
        return ParsedI64.Ok(n);
    }
    if n == I64_MIN {
        return ParsedI64.Bad;
    }
    return ParsedI64.Ok(-n);
}

/// `base` to the power `exp` by repeated squaring. Traps (R0001) when the result does not fit in i64.
fn pow_i64(base: i64, exp: i64) -> i64
    requires exp >= 0
{
    var result: i64 = 1;
    var b: i64 = base;
    var e: i64 = exp;
    while e > 0 limit 64 {
        if e % 2 == 1 {
            result *= b;
        }
        e /= 2;
        if e > 0 {
            b *= b;
        }
    }
    return result;
}

/// The next number of a pseudo-random sequence from 0 to 2147483647 (a linear congruential generator
/// with the C standard's example constants). ALang has no randomness: take the first seed as a tool
/// argument, and use next % n for a number below n.
fn next_random(seed: i64) -> i64
    requires 0 <= seed and seed < 2147483648
{
    return (seed * 1103515245 + 12345) % 2147483648;
}

// ------------------------------------------------------------------ lists

/// Sorts `xs` in place, smallest first. Insertion sort: stable, and quick enough for a few hundred
/// items; examples/sorting.al has merge sort and quicksort for more.
fn sort_i64(xs: mut list[i64; ITEMS]) {
    for i in 1..len(xs) {
        let v: i64 = xs[i];
        var j: i64 = i;
        while j > 0 and xs[j - 1] > v limit ITEMS {
            xs[j] = xs[j - 1];
            j -= 1;
        }
        xs[j] = v;
    }
}

/// Sorts `xs` in place in str_less order.
fn sort_strs(xs: mut list[str[TEXT]; ITEMS]) {
    for i in 1..len(xs) {
        let v: str[TEXT] = xs[i];
        var j: i64 = i;
        while j > 0 and str_less(v, xs[j - 1]) limit ITEMS {
            xs[j] = xs[j - 1];
            j -= 1;
        }
        xs[j] = v;
    }
}

fn contains_i64(xs: list[i64; ITEMS], v: i64) -> bool {
    for x in xs {
        if x == v {
            return true;
        }
    }
    return false;
}

fn reverse_i64(xs: list[i64; ITEMS]) -> list[i64; ITEMS] {
    var out: list[i64; ITEMS] = [];
    for k in 0..len(xs) {
        push(mut out, xs[len(xs) - 1 - k]);
    }
    return out;
}

// ------------------------------------------------------------------ a map as a list of entries

/// One key and its value. ALang has no maps or dictionaries: a list of entries, searched with a loop,
/// takes their place, and keeps the order the keys were added in.
struct Entry {
    key: str[TEXT],
    value: i64,
}

enum MaybeI64 {
    None,
    Some(i64),
}

/// The value stored under `key`: a dictionary lookup.
fn lookup(entries: list[Entry; ITEMS], key: str[TEXT]) -> MaybeI64 {
    for e in entries {
        if e.key == key {
            return MaybeI64.Some(e.value);
        }
    }
    return MaybeI64.None;
}

/// Sets the value under `key`, adding the key if it is new. Traps (R0004) when a new key does not fit.
fn store(entries: mut list[Entry; ITEMS], key: str[TEXT], value: i64) {
    for i in 0..len(entries) {
        if entries[i].key == key {
            entries[i].value = value;
            return;
        }
    }
    push(mut entries, Entry(key: key, value: value));
}

fn main() {
    print(split("a,b,,c", ','));
    print(words("  the quick  brown fox "));
    print("[", trim("  padded  "), "]");
    print(to_upper("Hello, World"), " ", to_lower("Hello, World"));
    print(pad_left("42", 6, '0'), " ", index_of("haystack", "st"));
    print(parse_i64("-9223372036854775808"), " ", parse_i64("12x"));
    print(pow_i64(3, 13));
    var xs: list[i64; ITEMS] = [5, -2, 9, 0];
    sort_i64(mut xs);
    print(xs, " ", reverse_i64(xs));
    var names: list[str[TEXT]; ITEMS] = ["pear", "apple", "Zebra", "app"];
    sort_strs(mut names);
    print(join(names, ", "));
    var stock: list[Entry; ITEMS] = [];
    store(mut stock, "apples", 3);
    store(mut stock, "pears", 5);
    store(mut stock, "apples", 7);
    print(stock);
    print(lookup(stock, "apples"), " ", lookup(stock, "kiwis"));
}

test "str_less orders byte by byte, a prefix first" {
    assert str_less("apple", "banana");
    assert str_less("app", "apple");
    assert str_less("Zebra", "apple");
    assert not str_less("apple", "apple");
    assert not str_less("b", "a");
}

test "starts_with, ends_with and index_of" {
    assert starts_with("haystack", "hay");
    assert not starts_with("hay", "haystack");
    assert ends_with("haystack", "stack");
    assert not ends_with("stack", "haystack");
    assert index_of("haystack", "st") == 3;
    assert index_of("haystack", "needle") == -1;
    assert index_of("abc", "") == 0;
    assert index_of("ab", "abc") == -1;
}

test "substring, trim and case" {
    assert substring("haystack", 3, 8) == "stack";
    assert substring("abc", 1, 1) == "";
    assert trim("  a b \n") == "a b";
    assert trim("   ") == "";
    assert trim("") == "";
    assert to_upper("abc-XYZ 1") == "ABC-XYZ 1";
    assert to_lower("abc-XYZ 1") == "abc-xyz 1";
}

test "split keeps empty parts, words drops them" {
    let parts: list[str[TEXT]; ITEMS] = split("a,,b", ',');
    assert len(parts) == 3;
    assert parts[1] == "";
    assert len(split("", ',')) == 1;
    let ws: list[str[TEXT]; ITEMS] = words(" x  yz ");
    assert len(ws) == 2;
    assert ws[1] == "yz";
    assert len(words("   ")) == 0;
}

test "join and pad_left" {
    assert join(split("a,b,c", ','), " + ") == "a + b + c";
    assert join([], ", ") == "";
    assert pad_left("42", 5, '0') == "00042";
    assert pad_left("toolong", 3, ' ') == "toolong";
}

test "parse_i64 accepts exactly the i64 range" {
    assert parse_i64("0") == ParsedI64.Ok(0);
    assert parse_i64("+17") == ParsedI64.Ok(17);
    assert parse_i64("-17") == ParsedI64.Ok(-17);
    assert parse_i64("9223372036854775807") == ParsedI64.Ok(9223372036854775807);
    assert parse_i64("-9223372036854775808") == ParsedI64.Ok(I64_MIN);
    assert parse_i64("9223372036854775808") == ParsedI64.Bad;
    assert parse_i64("-9223372036854775809") == ParsedI64.Bad;
    assert parse_i64("") == ParsedI64.Bad;
    assert parse_i64("-") == ParsedI64.Bad;
    assert parse_i64(" 1") == ParsedI64.Bad;
    assert parse_i64("1.5") == ParsedI64.Bad;
}

test "pow_i64" {
    assert pow_i64(2, 0) == 1;
    assert pow_i64(2, 10) == 1024;
    assert pow_i64(-3, 3) == -27;
    assert pow_i64(-2, 63) == I64_MIN;
    assert pow_i64(1, 1000000000000) == 1;
}

test "next_random repeats for the same seed and stays in range" {
    var x: i64 = 2024;
    for i in 0..100 {
        x = next_random(x);
        assert 0 <= x and x < 2147483648;
    }
    assert next_random(0) == 12345;
    assert next_random(2024) == next_random(2024);
}

test "sorting" {
    var xs: list[i64; ITEMS] = [3, 1, 2, 3, -5];
    sort_i64(mut xs);
    assert xs == [-5, 1, 2, 3, 3];
    var none: list[i64; ITEMS] = [];
    sort_i64(mut none);
    assert len(none) == 0;
    var names: list[str[TEXT]; ITEMS] = ["b", "a", "ab"];
    sort_strs(mut names);
    assert names == ["a", "ab", "b"];
}

test "contains and reverse" {
    assert contains_i64([1, 2, 3], 2);
    assert not contains_i64([], 2);
    assert reverse_i64([1, 2, 3]) == [3, 2, 1];
}

test "a list of entries works as a map" {
    var m: list[Entry; ITEMS] = [];
    store(mut m, "a", 1);
    store(mut m, "b", 2);
    store(mut m, "a", 3);
    assert len(m) == 2;
    assert lookup(m, "a") == MaybeI64.Some(3);
    assert lookup(m, "c") == MaybeI64.None;
}
