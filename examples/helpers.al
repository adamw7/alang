// Helpers the standard library leaves out, each written once and tested. Import this file, as
// examples/tally.al does with `import "helpers.al";` (its main and tests stay out of your program), or,
// as there are no generics, copy the helpers you need and change the capacities to fit your data: TEXT
// and ITEMS below, or the types themselves. For split, trim, index_of, sort, pow and the rest, call the
// standard library (docs/SPEC.md, section 9).
//
// A read-only `str[N]` or `list[T; N]` parameter also accepts a value of smaller capacity, so these
// helpers take any text up to TEXT bytes and any list up to ITEMS items.

const TEXT: i64 = 64;
const ITEMS: i64 = 16;
const I64_MIN: i64 = -9223372036854775807 - 1;

// ------------------------------------------------------------------ text

/// Whether `a` sorts before `b`, byte by byte: the order of C's strcmp, of `<` on ASCII strings in
/// Python or Rust, and of sort(mut xs) on strs. ALang's `<` works only on numbers.
fn str_less(a: str[TEXT], b: str[TEXT]) -> bool {
    for i in 0..min(len(a), len(b)) {
        if a[i] != b[i] {
            return a[i] < b[i];
        }
    }
    return len(a) < len(b);
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
/// characters, nothing outside the i64 range. i64(s) traps on such text; this returns Bad instead.
/// To turn a number into text, use append(mut s, n).
fn parse_i64(s: str[TEXT]) -> ParsedI64 {
    if is_int(s) {
        return ParsedI64.Ok(i64(s));
    }
    return ParsedI64.Bad;
}

/// The next number of a pseudo-random sequence from 0 to 2147483647 (a linear congruential generator
/// with the C standard's example constants). ALang has no randomness: take the first seed as a tool
/// argument, and use next % n for a number below n.
fn next_random(seed: i64) -> i64
    requires 0 <= seed and seed < 2147483648
{
    return (seed * 1103515245 + 12345) % 2147483648;
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
    print(str_less("Zebra", "apple"), " ", pad_left("42", 6, '0'));
    print(parse_i64("-9223372036854775808"), " ", parse_i64("12x"));
    print(next_random(2024));
    var stock: list[Entry; ITEMS] = [];
    store(mut stock, "apples", 3);
    store(mut stock, "pears", 5);
    store(mut stock, "apples", 7);
    print(stock);
    print(lookup(stock, "apples"), " ", lookup(stock, "kiwis"));
}

test "str_less orders byte by byte, a prefix first, as sort does" {
    assert str_less("apple", "banana");
    assert str_less("app", "apple");
    assert str_less("Zebra", "apple");
    assert not str_less("apple", "apple");
    assert not str_less("b", "a");
    var names: list[str[TEXT]; ITEMS] = ["pear", "apple", "Zebra", "app"];
    sort(mut names);
    for i in 1..len(names) {
        assert str_less(names[i - 1], names[i]);
    }
}

test "pad_left" {
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

test "next_random repeats for the same seed and stays in range" {
    var x: i64 = 2024;
    for i in 0..100 {
        x = next_random(x);
        assert 0 <= x and x < 2147483648;
    }
    assert next_random(0) == 12345;
    assert next_random(2024) == next_random(2024);
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
