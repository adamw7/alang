// expect: E0401
fn a(x: i64) -> i64 {
    let f: fn(i64) -> i64 = b;
    return f(x);
}

fn b(x: i64) -> i64 {
    let g: fn(i64) -> i64 = a;
    return if x > 0 then g(x - 1) else 0;
}

fn main() {
    print(a(3));
}
