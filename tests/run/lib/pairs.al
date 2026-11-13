// A library with a generic struct, imported under a name by generics_import.al.
struct Pair<A, B> {
    first: A,
    second: B,
}

fn half(n: i64) -> Option<i64> {
    if n % 2 == 0 {
        return Option.Some(n / 2);
    }
    return Option.None;
}
