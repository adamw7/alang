// expect: E0218
struct Boxed<T> {
    value: T = 0,
}

fn main() {
    let b: Boxed<i64> = Boxed(value: 1);
    print(b);
}
