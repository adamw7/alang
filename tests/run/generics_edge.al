// Generic constants, nested type variables, generic struct literals passed to generic functions.
struct Stack<T> {
    items: list[T; 4],
}

const NONE: Option<i64> = Option.None;
const ORIGIN: Stack<u8> = Stack(items: [1, 2]);

fn flatten<T>(m: Option<Option<T>>) -> Option<T> {
    return match m {
        Option.Some(Option.Some(v)) => Option.Some(v),
        _ => Option.None,
    };
}

fn top<T>(s: Stack<T>) -> Option<T> {
    if len(s.items) == 0 {
        return Option.None;
    }
    return Option.Some(s.items[len(s.items) - 1]);
}

fn main() {
    print(NONE, " ", ORIGIN, " ", top(ORIGIN), " ", top(Stack(items: [true])));
    print(flatten(Option.Some(Option.Some("x"))), " ", flatten(Option.Some(NONE)) == Option.Some(1));
    let e: Stack<str[2]> = Stack(items: []);
    print(top(e), " ", e == Stack(items: []));
    var counts: map[str[4], Option<i64>; 4] = [];
    counts["a"] = Option.Some(1);
    counts["b"] = Option.None;
    print(counts);
}
