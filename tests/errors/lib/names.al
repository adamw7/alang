// Imported by tests/errors: declares names that the importing programs declare again.
const LIMIT_ITEMS: i64 = 4;

struct Pair {
    a: i64,
    b: i64,
}

fn total(p: Pair) -> i64 {
    return p.a + p.b;
}
