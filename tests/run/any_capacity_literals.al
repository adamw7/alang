// Literals passed where a parameter takes any capacity: of a set, a map, a tuple, or of elements that have a
// `_` of their own. Each `_` gets what the literal needs; cap() inside is what the call made.
fn longest(xs: list[str[_]; _]) -> i64 {
    var best: i64 = 0;
    for x in xs {
        best = max(best, len(x));
    }
    return best;
}

fn distinct(words: set[str[_]; _]) -> i64 {
    return len(words);
}

fn total(xs: set[i64; _]) -> i64 {
    var t: i64 = 0;
    for x in xs {
        t += x;
    }
    return t;
}

fn entries(m: map[str[4], i64; _]) -> i64 {
    return len(m);
}

fn rows(r: [list[i64; _]; 2]) -> i64 {
    return len(r[0]) + len(r[1]);
}

fn weigh(pairs: list[(i64, str[_]); _]) -> i64 {
    var t: i64 = 0;
    for (n, s) in pairs {
        t += n * len(s);
    }
    return t;
}

fn labelled(t: (list[i64; _], str[_])) -> i64 {
    return sum(t.0) + len(t.1);
}

fn size<T>(xs: set[T; _]) -> i64 {
    return len(xs);
}

fn main() {
    let name: str[8] = "seven77";
    print(longest(["a", "bbb"]), " ", longest([name, "x"]));
    print(distinct(["b", "a", "b"]), " ", total([3, 1, 3]), " ", total([]), " ", entries([]));
    print(rows([[1], [2, 3]]), " ", weigh([(2, "ab"), (3, "xyz")]), " ", labelled(([1, 2, 3], "hi")));
    print(size([1, 2, 2]), " ", size(["x"]));
}
