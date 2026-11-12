// expect: E0101 E0201 E0203 E0216 E0217 E0601
// Type parameters stand for any type, so only what works on every type is allowed; functions are passed by name.
fn add<T>(a: T, b: T) -> T {
    return a + b;
}

fn less<T>(a: T, b: T) -> bool {
    return a < b;
}

fn make<T>() -> i64 {
    return 0;
}

fn apply(f: fn(i64) -> i64, x: i64) -> i64 {
    return f(x);
}

fn half(x: i64) -> f64 {
    return f64(x) / 2.0;
}

tool fn pick<T>(x: T) -> T {
    return x;
}

fn main() {
    let f: fn(i64) -> i64 = half;
    print(apply(half, 2), apply(abs, 2), apply(nothing, 2), add(1, 2), less(1, 2), make(), pick(1));
}
