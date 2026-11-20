// expect: E0309
// The else block runs when the pattern does not match: after it, `n` would have no value.
fn parse(s: str[8]) -> i64 {
    let Option.Some(n): Option<i64> = parse_i64(s) else {
        print("not a number: ", s);
    };
    return n;
}

fn main() {
    print(parse("12"));
}
