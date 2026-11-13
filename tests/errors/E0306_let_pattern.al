// expect: E0306
enum MaybeI64 {
    None,
    Some(i64),
}

fn main() {
    let m: MaybeI64 = MaybeI64.Some(3);
    let MaybeI64.Some(v): MaybeI64 = m;
    print(v);
}
