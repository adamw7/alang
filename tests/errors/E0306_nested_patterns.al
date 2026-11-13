// expect: E0306
enum MaybeI64 {
    None,
    Some(i64),
}

enum Color {
    Red,
    Green,
    Blue,
}

fn first(m: MaybeI64) -> i64 {
    match m {
        MaybeI64.Some(0) => { return 0; }
        MaybeI64.None => { return -1; }
    }
}

fn pick(c: Color, on: bool) -> i64 {
    return match (c, on) {
        (Color.Red, true) => 1,
        (Color.Green, _) => 2,
        (_, false) => 3,
    };
}

fn main() {
    print(first(MaybeI64.None), pick(Color.Red, true));
}
