// A generic struct of an imported file, under its name, beside one of this file; Option in both.
import "lib/pairs.al" as p;

struct Pair<A, B> {
    left: A,
    right: B,
}

fn main() {
    let a: p.Pair<i64, bool> = p.Pair(first: 1, second: true);
    let b: Pair<i64, bool> = Pair(left: 2, right: false);
    let h: Option<i64> = p.half(6);
    print(a, " ", b, " ", h, " ", p.half(5));
}
