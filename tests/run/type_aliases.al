// type Name = T; gives a type another name, with type parameters if it has them.
import "lib/aliased.al" as geo;

type Grid = [[i64; 3]; 3];
type Word = str[16];
type Counts = map[Word, i64; 8];
type Pair<T> = (T, T);
type Pred = fn(i64) -> bool;
type Maybe<T> = Option<Pair<T>>;

fn trace(g: Grid) -> i64 {
    var t: i64 = 0;
    for i in 0..3 {
        t += g[i][i];
    }
    return t;
}

fn swap_pair<T>(p: Pair<T>) -> Pair<T> {
    return (p.1, p.0);
}

fn is_odd(x: i64) -> bool {
    return x % 2 != 0;
}

fn count_where(xs: list[i64; _], keep: Pred) -> i64 {
    return count_if(xs, keep);
}

fn main() {
    var g: Grid = [[0; 3]; 3];
    for i in 0..3 {
        g[i][i] = i + 1;
    }
    print(trace(g));
    var c: Counts = [];
    let w: Word = "hi";
    c[w] = get_or(c, w, 0) + 1;
    print(c);
    let p: Pair<str[4]> = ("a", "b");
    print(swap_pair(p));
    print(count_where([1, 2, 3, 5], is_odd));
    let m: Maybe<i64> = Option.Some((1, 2));
    print(m);
    let pt: geo.Point = geo.origin();
    let path: geo.Path = [pt, geo.Point(x: 2, y: 3)];
    print(geo.length(path));
}
