// A call fixes a type parameter from the arguments that give one, in any order: `[]`, Option.None and Tree.Empty
// give none, so the literal beside them decides, before or after them.
enum Tree<T> {
    Leaf(T),
    Empty,
}

fn first_or<T>(xs: list[T; _], d: T) -> T {
    return if len(xs) > 0 then xs[0] else d;
}

fn or_first<T>(d: T, xs: list[T; _]) -> T {
    return if len(xs) > 0 then xs[0] else d;
}

fn or_else<T>(o: Option<T>, d: T) -> T {
    return match o { Option.Some(v) => v, Option.None => d };
}

fn leaf_or<T>(t: Tree<T>, d: T) -> T {
    return match t { Tree.Leaf(v) => v, Tree.Empty => d };
}

fn both<T>(a: list[T; _], b: list[T; _]) -> i64 {
    return len(a) + len(b);
}

fn main() {
    print(first_or([], 5), " ", first_or([], -5), " ", first_or([], 2.5), " ", first_or([], "s"), " ", or_first(6, []));
    print(or_else(Option.None, 7), " ", leaf_or(Tree.Empty, 0.5), " ", both([], [1]), " ", both([1, 2], []));
    let x: u8 = 9;
    print(first_or([], x), " ", or_else(Option.Some(3), 4));
}
