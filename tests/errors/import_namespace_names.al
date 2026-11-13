// expect: E0101 E0102 E0103
// A file imported with `as` keeps its names apart: they are reached as n.name, and only so.
import "lib/names.al" as n;

const LIMIT_ITEMS: i64 = 8;

fn total(x: i64) -> i64 {
    return x;
}

fn main() {
    let p: n.Pair = n.Pair(a: 1, b: 2);
    print(n.LIMIT_ITEMS, " ", LIMIT_ITEMS, " ", n.total(p), " ", total(3));
    print(n.nothing(1));
    let q: n.Missing = n.Pair(a: 1, b: 1);
    print(n.MAX, q);
}
