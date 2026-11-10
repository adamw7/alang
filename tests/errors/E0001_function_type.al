// expect: E0001
fn apply(f: fn(i64) -> i64, x: i64) -> i64 {
    return f(x);
}
