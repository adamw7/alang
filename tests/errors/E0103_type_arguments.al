// expect: E0103
struct Pair<A, B> {
    first: A,
    second: B,
}

struct Point {
    x: i64,
}

fn f(m: Option, p: Pair<i64>, q: Point<i64>) {
    print(m, p, q);
}

fn main() {
    print(1);
}
