// Money, imported under a name: the same names as geo.al's, in a scope of their own.
const SCALE: i64 = 100;

struct Point {
    cents: i64,
}

fn describe(p: Point) -> str[32] {
    var out: str[32] = "";
    append(mut out, "$", p.cents / SCALE, ".", pad_left(p.cents % SCALE, 2, '0'));
    return out;
}
