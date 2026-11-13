// expect: E0217
fn size_of(m: Option<str[_]>) -> i64 {
    return match m {
        Option.Some(s) => len(s),
        Option.None => 0,
    };
}

fn main() {
    print(size_of(Option.Some("a")));
}
