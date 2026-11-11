// The standard library's text functions, and conversions from text to integers.
const CSV: list[str[8]; 4] = split("a,bb,,c", ",");
const LINE: str[16] = join(CSV, '|');
const LOUD: str[5] = to_upper("hello");

struct Pair {
    key: str[32],
    value: i64,
}

/// Parses `key=value` pairs separated by `;`, skipping malformed ones.
fn parse_pairs(text: str[64]) -> list[Pair; 8] {
    var out: list[Pair; 8] = [];
    let items: list[str[32]; 8] = split(text, ';');
    for item in items {
        let kv: list[str[32]; 2] = split(trim(item), "=");
        if len(kv) == 2 and is_int(trim(kv[1])) {
            let key: str[32] = trim(kv[0]);
            push(mut out, Pair(key: slice(key, 0, min(len(key), 8)), value: i64(trim(kv[1]))));
        }
    }
    return out;
}

fn main() {
    print(CSV, " ", LINE, " ", LOUD);

    let s: str[32] = "Hello, World";
    print(contains(s, "World"), " ", contains(s, "world"), " ", contains(s, ','), " ", contains(s, ""));
    print(index_of(s, "o"), " ", index_of(s, 'W'), " ", index_of(s, "xyz"), " ", index_of(s, ""), " ", index_of("", ""));
    print(starts_with(s, "Hell"), " ", starts_with(s, 'H'), " ", starts_with(s, "World"), " ", starts_with("", ""));
    print(ends_with(s, "World"), " ", ends_with(s, 'd'), " ", ends_with("ab", "abc"), " ", ends_with(s, ""));
    print(starts_with("abc", "abc"), " ", ends_with("abc", "abc"), " ", starts_with("abc", "abcd"), " ", contains("abc", "abc"));

    // Results that exactly fill their capacity.
    let exact: list[str[4]; 2] = split("abcd,efgh", ',');
    let joined: str[9] = join(exact, ',');
    let widened: str[7] = replace("a-b-c", "-", "--");
    let front: str[4] = replace("ab", "a", "xyz");
    print(exact, " ", joined, " ", widened, " ", front);

    print("[", slice(s, 0, 5), "] [", slice(s, 7, 12), "] [", slice(s, 3, 3), "] [", slice(s, 0, len(s)), "]");
    print("[", trim("  \t padded \n "), "] [", trim(""), "] [", trim("   "), "] [", trim("x"), "]");
    print(to_upper("MiXeD 123 ä"), " ", to_lower("MiXeD 123 Ä"), " ", to_upper('q'), " ", to_lower('Q'), " ", to_upper('?'));

    let parts: list[str[8]; 8] = split("one two  three", " ");
    print(parts, " ", len(parts));
    let empty: list[str[4]; 2] = split("", ",");
    print(empty, " ", len(empty));
    let ends: list[str[4]; 4] = split(",x,", ',');
    print(ends);
    let multi: list[str[8]; 4] = split("a::b::::c", "::");
    print(multi);
    let ws: list[str[8]; 8] = words("  one two\tthree\n\nfour  ");
    print(ws, " ", len(ws));
    let none: list[str[8]; 1] = words(" \t\n ");
    print(none, " ", len(none));

    let names: [str[8]; 3] = ["ann", "bob", "cy"];
    var line: str[32] = join(names, ", ");
    print(line);
    line = join(names, "");
    print(line);
    let nothing: list[str[4]; 2] = [];
    let blank: str[4] = join(nothing, "-");
    print("[", blank, "]");

    let r: str[32] = replace("a-b-c", "-", " + ");
    print(r);
    let shrink: str[8] = replace("aaaa", "aa", "b");
    print(shrink, " ", len(shrink));
    let same: str[8] = replace("abc", "x", "yyyyyyyy");
    print(same);
    let swapped: str[8] = replace("a.b.c", '.', '/');
    print(swapped);
    let gone: str[8] = replace("banana", "an", "");
    print(gone);

    let texts: [str[24]; 12] = ["0", "-0", "+42", "-17", "007", "9223372036854775807", "-9223372036854775808",
        "9223372036854775808", "", "-", "1.5", " 1"];
    for t in texts {
        print("\"", t, "\" is_int ", is_int(t));
    }
    print(i64("-9223372036854775808"), " ", u64("18446744073709551615"), " ", u8("+255"), " ", i8("-128"), " ", u8("-0"));
    let n: i32 = i32("123") + 1;
    print(n);

    print(parse_pairs("a=1; b = 22 ;bad; c=x; long_key_name=-3"));
}

test "split and join round trip" {
    let parts: list[str[8]; 8] = split("x,y,,z", ",");
    let back: str[16] = join(parts, ",");
    assert back == "x,y,,z";
    assert len(parts) == 4;
}
