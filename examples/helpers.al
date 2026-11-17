// Helpers the standard library leaves out, each written once and tested. Import this file, as
// examples/tally.al does with `import "helpers.al";` (its main and tests stay out of your program), or copy
// the helpers you need. Their parameters take any capacity (`str[_]`, `list[Entry; _]`), so they work on
// your strs and lists as they are; change Entry if your keys or values have other types. For ordering
// strs (`<`), padding (pad_left), split, trim, index_of, parse_i64, sort, sort_by, pow and the rest, use the
// language and the standard library (docs/SPEC.md, section 9).

const TEXT: i64 = 64;

// ------------------------------------------------------------------ numbers

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

/// The value stored under `key`: a dictionary lookup, in a list of entries of any capacity.
fn lookup(entries: list[Entry; _], key: str[_]) -> Option<i64> {
    for e in entries {
        if e.key == key {
            return Option.Some(e.value);
        }
    }
    return Option.None;
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
    print(next_random(2024));
    var stock: list[Entry; 16] = [];
    store(mut stock, "apples", 3);
    store(mut stock, "pears", 5);
    store(mut stock, "apples", 7);
    print(stock);
    print(lookup(stock, "apples"), " ", lookup(stock, "kiwis"));
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
    assert lookup(m, "a") == Option.Some(3);
    assert lookup(m, "c") == Option.None;
    var big: list[Entry; 100] = [];
    let long_key: str[200] = "a key in a long buffer";
    store(mut big, long_key, 9);
    assert lookup(big, "a key in a long buffer") == Option.Some(9);
}
