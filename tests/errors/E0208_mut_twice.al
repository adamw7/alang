// expect: E0208
// A `mut` number is a reference, not a copy: with i == j both parameters would be the same element.
fn swap(a: mut i64, b: mut i64) {
    a = a + b;
    b = a - b;
    a = a - b;
}

fn bump(x: mut i64, y: mut i64) {
    x += 1;
    y += 1;
}

struct Pair {
    a: i64,
    b: i64,
}

fn main() {
    var xs: list[i64; 4] = [5, 7];
    let i: i64 = 1;
    let j: i64 = 1;
    swap(mut xs[i], mut xs[j]);
    var n: i64 = 0;
    bump(mut n, mut n);
    var p: Pair = Pair(a: 0, b: 0);
    bump(mut p.a, mut p.b);
    bump(mut xs[n], mut n);
    print(xs, n, p);
}
