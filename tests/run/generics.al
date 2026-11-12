// Type parameters and functions as parameters: each call runs a copy made for its types and functions.
struct Point {
    x: i64,
    y: i64,
}

/// The largest element, by the order of sort.
fn largest<T>(xs: list[T; _]) -> T
    requires len(xs) > 0
{
    var best: T = xs[0];
    for x in xs {
        if compare(x, best) > 0 {
            best = x;
        }
    }
    return best;
}

fn swap<A, B>(p: (A, B)) -> (B, A) {
    return (p.1, p.0);
}

fn count_if<T>(xs: list[T; _], keep: fn(T) -> bool) -> i64 {
    var n: i64 = 0;
    for x in xs {
        if keep(x) {
            n += 1;
        }
    }
    return n;
}

fn map_into<T, U>(xs: list[T; _], f: fn(T) -> U, out: mut list[U; _]) {
    clear(mut out);
    for x in xs {
        push(mut out, f(x));
    }
}

/// Sorts by a key that a function computes, smallest first; equal keys keep their order.
fn sort_by_key<T, K>(xs: mut list[T; _], key: fn(T) -> K) {
    for i in 1..len(xs) {
        var j: i64 = i;
        while j > 0 and compare(key(xs[j - 1]), key(xs[j])) > 0 limit len(xs) {
            let tmp: T = xs[j];
            xs[j] = xs[j - 1];
            xs[j - 1] = tmp;
            j -= 1;
        }
    }
}

/// A function passed on to another.
fn count_twice<T>(xs: list[T; _], keep: fn(T) -> bool) -> i64 {
    return count_if(xs, keep) * 2;
}

fn is_even(x: i64) -> bool {
    return x % 2 == 0;
}

fn is_long(s: str[8]) -> bool {
    return len(s) > 3;
}

fn square(x: i64) -> i64 {
    return x * x;
}

fn norm(p: Point) -> i64 {
    return p.x * p.x + p.y * p.y;
}

fn shout(s: str[8]) -> str[9] {
    var out: str[9] = to_upper(s);
    push(mut out, '!');
    return out;
}

fn main() {
    let nums: list[i64; 8] = [3, 8, 1, 6];
    let words: list[str[8]; 4] = ["ab", "cdef", "ghijk"];
    print(largest(nums), " ", largest(words), " ", largest([2.5, -1.0]), " ", largest([Point(x: 1, y: 9), Point(x: 2, y: 0)]));
    print(swap((1, "one")), " ", swap(("a", true)));
    print(count_if(nums, is_even), " ", count_if(words, is_long), " ", count_twice(nums, is_even));
    var squares: list[i64; 8] = [];
    map_into(nums, square, mut squares);
    var loud: list[str[9]; 4] = [];
    map_into(words, shout, mut loud);
    print(squares, " ", loud);
    var points: list[Point; 4] = [Point(x: 3, y: 4), Point(x: 1, y: 1), Point(x: 0, y: 2)];
    sort_by_key(mut points, norm);
    print(points);
    print(compare(1, 2), " ", compare("b", "a"), " ", compare(Point(x: 1, y: 1), Point(x: 1, y: 1)));
}

test "largest of a list of one" {
    let one: list[u8; 1] = [7];
    assert largest(one) == 7;
}
