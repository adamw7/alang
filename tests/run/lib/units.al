// Imported by tests/run/imports.al directly and through geometry.al: read once.
const SCALE: i64 = 1;

fn label(n: i64) -> str[24] {
    var s: str[24] = "";
    append(mut s, n, " m2");
    return s;
}

/// Traps on an empty list, in this file.
fn first(xs: list[i64; 4]) -> i64 {
    return xs[0];
}
