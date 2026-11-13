// Structs and enums with type parameters, and the built-in Option<T> and Result<T, E>.
struct Pair<A, B> {
    first: A,
    second: B,
}

enum Tree<T> {
    Leaf(T),
    Empty,
}

struct Stack<T> {
    items: list[T; 8],
}

fn find(xs: list[i64; _], want: i64) -> Option<i64> {
    for i, x in xs {
        if x == want {
            return Option.Some(i);
        }
    }
    return Option.None;
}

fn parse(s: str[_]) -> Result<i64, str[32]> {
    let t: str[32] = str[32](trim(s));
    if is_int(t) {
        return Result.Ok(i64(t));
    }
    var why: str[32] = "not a number: ";
    append(mut why, t);
    return Result.Err(why);
}

fn unwrap_or<T>(m: Option<T>, d: T) -> T {
    return match m {
        Option.Some(v) => v,
        Option.None => d,
    };
}

fn swap<A, B>(p: Pair<A, B>) -> Pair<B, A> {
    return Pair(first: p.second, second: p.first);
}

fn push_all<T>(s: mut Stack<T>, xs: list[T; _]) {
    for x in xs {
        push(mut s.items, x);
    }
}

fn main() {
    let xs: list[i64; 8] = [4, 5, 6];
    print(find(xs, 5), " ", find(xs, 9));
    print(unwrap_or(find(xs, 6), -1), " ", unwrap_or(find(xs, 7), -1));
    print(unwrap_or(Option.None, "none"), " ", unwrap_or(Option.Some(2.5), 0.0));
    print(parse(" 42 "), " ", parse("x"));
    match parse("17") {
        Result.Ok(n) if n > 10 => { print("big ", n); }
        Result.Ok(n) => { print("small ", n); }
        Result.Err(e) => { print(e); }
    }
    let p: Pair<i64, str[4]> = Pair(first: 1, second: "one");
    print(p, " ", swap(p));
    let q: Pair<Option<i64>, bool> = Pair(first: Option.None, second: true);
    print(q);
    let nested: Option<Option<i64>> = Option.Some(Option.Some(3));
    if let Option.Some(Option.Some(v)) = nested {
        print("nested ", v);
    }
    var s: Stack<str[2]> = Stack(items: []);
    push_all(mut s, ["a", "b"]);
    print(s);
    let t: Tree<u8> = Tree.Leaf(7);
    print(t, " ", t == Tree.Leaf(7), " ", Tree.Empty == t);
    var opts: list[Option<i64>; 4] = [Option.Some(3), Option.None, Option.Some(1)];
    sort(mut opts);
    print(opts);
}

test "a value takes its type arguments from where it goes" {
    let none: Option<str[4]> = Option.None;
    assert none == Option.None;
    assert Option.Some(3) != Option.None;
    let r: Result<u8, bool> = Result.Err(true);
    match r {
        Result.Ok(_) => { panic("not ok"); }
        Result.Err(e) => { assert e; }
    }
}

test "instances of one declaration are different types" {
    let a: Pair<i64, bool> = Pair(first: 1, second: true);
    let b: Pair<bool, i64> = swap(a);
    assert b.first and b.second == 1;
}

test "a missing value traps where it is unwrapped" traps R0010 {
    let m: Option<i64> = Option.None;
    match m {
        Option.Some(_) => { }
        Option.None => { panic("no value"); }
    }
}
