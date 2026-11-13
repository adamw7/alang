// expect: E0201
enum MaybeI64 {
    None,
    Some(i64),
}

fn main() {
    let m: MaybeI64 = MaybeI64.None;
    let text: i64 = match m {
        MaybeI64.Some(v) => v,
        MaybeI64.None => "none",
    };
    print(text);
}
