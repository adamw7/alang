// expect: E0601
fn inc(x: i64) -> i64 {
    return x + 1;
}

/// Picks a function.
tool fn pick(n: i64) -> fn(i64) -> i64 {
    return inc;
}
