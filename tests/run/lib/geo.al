// Geometry, imported under a name: its declarations do not clash with money.al's.
import "geo_units.al";

const SCALE: i64 = 10;

struct Point {
    x: i64,
    y: i64,
}

enum Kind {
    Origin,
    Axis(i64),
    Other,
}

fn describe(p: Point) -> str[32] {
    var out: str[32] = "";
    append(mut out, "(", p.x * SCALE, ", ", p.y * SCALE, ") ", unit_name());
    return out;
}

fn kind(p: Point) -> Kind {
    if p.x == 0 and p.y == 0 {
        return Kind.Origin;
    }
    if p.x == 0 or p.y == 0 {
        return Kind.Axis(p.x + p.y);
    }
    return Kind.Other;
}

fn is_far(p: Point) -> bool {
    return p.x * p.x + p.y * p.y > 100;
}
