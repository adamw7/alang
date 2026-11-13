// Tuples: fixed groups of values of different types, read by position.
fn divmod(a: i64, b: i64) -> (i64, i64)
    requires b != 0
{
    return (a / b, a % b);
}

/// The smallest and largest of a list, and where the smallest is.
fn extremes(xs: list[i64; _]) -> (i64, i64, i64)
    requires len(xs) > 0
{
    var lo: i64 = xs[0];
    var hi: i64 = xs[0];
    var at: i64 = 0;
    for i, x in xs {
        if x < lo {
            lo = x;
            at = i;
        }
        hi = max(hi, x);
    }
    return (lo, hi, at);
}

fn main() {
    let (q, r): (i64, i64) = divmod(17, 5);
    print(q, " ", r);
    let p: (str[8], i64) = ("ann", 7);
    print(p, " ", p.0, " ", p.1);
    let ext: (i64, i64, i64) = extremes([4, -2, 9, -2]);
    let (lo, _, at): (i64, i64, i64) = ext;
    print(ext, " ", lo, " ", at);
    var scores: list[(str[8], i64); 4] = [("cy", 3), ("ann", 9), ("bob", 3)];
    sort(mut scores);
    print(scores);
    scores[0].1 += 10;
    print(scores[0], " ", scores[0] == ("bob", 13), " ", contains(scores, ("cy", 3)));
    let nested: ((i64, bool), str[4]) = ((1, true), "x");
    print(nested.0.1, " ", nested.0.0, " ", nested);
    var (a, b): (i64, f64) = (1, 2.5);
    a += 1;
    b = b * 2.0;
    print(a, " ", b);
    let words_and_counts: [(str[4], u8); 2] = [("a", 1), ("bb", 2)];
    for i, wc in words_and_counts {
        print(i, " ", wc.0, " ", wc.1);
    }
}
