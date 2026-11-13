// expect: E0401
// Passing a function to another that calls it is recursion when it passes itself.
fn apply(f: fn(i64) -> i64, x: i64) -> i64 {
    return f(x);
}

fn countdown(x: i64) -> i64 {
    if x <= 0 {
        return 0;
    }
    return apply(countdown, x - 1);
}

fn main() {
    print(countdown(3));
}
