// Helpers the standard library leaves out, each written once and tested. Import this file, as
// examples/tally.al does with `import "helpers.al";` (its main and tests stay out of your program), or copy
// the helpers you need. Their parameters take any capacity (`str[_]`, `list[Entry; _]`), so they work on
// your strs and lists as they are; change Entry if your keys or values have other types. For ordering
// strs (`<`), padding (pad_left), split, trim, index_of, sort, pow and the rest, use the language and the
// standard library (docs/SPEC.md, section 9).

const TEXT: i64 = 64;
const I64_MIN: i64 = -9223372036854775807 - 1;

// ------------------------------------------------------------------ numbers

enum ParsedI64 {
    Ok(i64),
    Bad,
}

/// A decimal integer with an optional sign, as strict as Rust's `parse::<i64>()`: no spaces, no other
/// characters, nothing outside the i64 range. i64(s) traps on such text; this returns Bad instead.
/// To turn a number into text, use append(mut s, n).
fn parse_i64(s: str[_]) -> ParsedI64 {
    if is_int(s) {
        return ParsedI64.Ok(i64(s));
    }
    return ParsedI64.Bad;
}

enum ParsedF64 {
    Ok(f64),
    Bad,
}

/// A decimal number such as 19.99, -0.5 or 42 that f64(s) converts exactly; f64(s) traps on other text,
/// this returns Bad instead. To write a number with a fixed number of decimals, use fixed(x, 2).
fn parse_f64(s: str[_]) -> ParsedF64 {
    if is_float(s) {
        return ParsedF64.Ok(f64(s));
    }
    return ParsedF64.Bad;
}

/// The next number of a pseudo-random sequence from 0 to 2147483647 (a linear congruential generator
/// with the C standard's example constants). ALang has no randomness: take the first seed as a tool
/// argument, and use next % n for a number below n.
fn next_random(seed: i64) -> i64
    requires 0 <= seed and seed < 2147483648
{
    return (seed * 1103515245 + 12345) % 2147483648;
}

// ------------------------------------------------------------------ a map in insertion order

/// One key and its value. A map[K, V; N] keeps its keys in order; a list of entries, searched with a loop,
/// keeps them in the order they were added in instead.
struct Entry {
    key: str[TEXT],
    value: i64,
}

enum MaybeI64 {
    None,
    Some(i64),
}

/// The value stored under `key`: a dictionary lookup, in a list of entries of any capacity.
fn lookup(entries: list[Entry; _], key: str[_]) -> MaybeI64 {
    for e in entries {
        if e.key == key {
            return MaybeI64.Some(e.value);
        }
    }
    return MaybeI64.None;
}

/// Sets the value under `key`, adding the key if it is new. Traps (R0004) when a new key does not fit:
/// the list is full, or the key is longer than TEXT bytes.
fn store(entries: mut list[Entry; _], key: str[_], value: i64) {
    for i in 0..len(entries) {
        if entries[i].key == key {
            entries[i].value = value;
            return;
        }
    }
    push(mut entries, Entry(key: str[TEXT](key), value: value));
}

fn main() {
    print(parse_i64("-9223372036854775808"), " ", parse_i64("12x"));
    print(parse_f64("19.99"), " ", parse_f64("1e5"));
    print(next_random(2024));
    var stock: list[Entry; 16] = [];
    store(mut stock, "apples", 3);
    store(mut stock, "pears", 5);
    store(mut stock, "apples", 7);
    print(stock);
    print(lookup(stock, "apples"), " ", lookup(stock, "kiwis"));
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

test "parse_f64 reads decimal numbers and nothing else" {
    assert parse_f64("19.99") == ParsedF64.Ok(19.99);
    assert parse_f64("-0.5") == ParsedF64.Ok(-0.5);
    assert parse_f64("42") == ParsedF64.Ok(42.0);
    assert parse_f64("1e5") == ParsedF64.Bad;
    assert parse_f64("") == ParsedF64.Bad;
    assert parse_f64("1,5") == ParsedF64.Bad;
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

test "a list of entries works as a map, at any capacity" {
    var m: list[Entry; 4] = [];
    store(mut m, "a", 1);
    store(mut m, "b", 2);
    store(mut m, "a", 3);
    assert len(m) == 2;
    assert lookup(m, "a") == MaybeI64.Some(3);
    assert lookup(m, "c") == MaybeI64.None;
    var big: list[Entry; 100] = [];
    let long_key: str[200] = "a key in a long buffer";
    store(mut big, long_key, 9);
    assert lookup(big, "a key in a long buffer") == MaybeI64.Some(9);
}
