// strip_prefix, strip_suffix, split_once and repeat.
fn main() {
    print(strip_prefix("v1.2", 'v'), "|", strip_prefix("1.2", 'v'), "|", strip_prefix("abc", ""), "|", strip_prefix("ab", "abc"));
    print(strip_suffix("a.txt", ".txt"), "|", strip_suffix("a.txt", "x"), "|", strip_suffix(".txt", ".txt"), "|");
    let path: str[16] = "/usr/bin/";
    let tail: str[16] = strip_suffix(strip_prefix(path, '/'), '/');
    print(tail, " ", len(tail), " ", cap(tail));
    match split_once("key=value=x", '=') {
        Option.Some(kv) => { print(kv.0, " -> ", kv.1); }
        Option.None => { print("no ="); }
    }
    print(split_once("abc", "=="), " ", split_once("a==b", "=="), " ", split_once("==", "=="), " ", split_once("", ','));
    let line: str[32] = "name: Ann Lee";
    if let Option.Some((k, v)) = split_once(line, ": ") {
        let both: (str[32], str[32]) = (k, v);
        print(both.0, "/", both.1, " ", cap(both.1));
    }
    // Stored as an Option of wider strs, the pieces have those capacities.
    let wide: Option<(str[8], str[32])> = split_once("a=b", '=');
    if let Option.Some((k, v)) = wide {
        print(k, " ", v, " ", cap(k), " ", cap(v));
    }
    // repeat writes its text out in print and append, and pads; stored, it takes the str's capacity.
    print(repeat("ab", 3), "[", repeat('-', 0), "]", repeat("", 5), "|");
    let rule: str[16] = repeat('=', 10);
    var t: str[32] = "";
    append(mut t, repeat("xy", 2), "!");
    print(rule, " ", t, " ", pad_left(repeat("z", 2), 5, '.'), " ", pad_right(repeat('*', 3), 5, '.'));
    let n: u8 = 4;
    let k: i32 = 2;
    print(repeat("o", n), repeat("-", k), repeat('|', u64(1)));
    var box: str[64] = "";
    for i in 1..=3 {
        append(mut box, repeat(' ', 3 - i), repeat('#', 2 * i - 1), "\n");
    }
    print(box);
}
