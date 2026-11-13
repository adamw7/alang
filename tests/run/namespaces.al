// import ... as name keeps a file's declarations apart: geo.describe and money.describe do not clash.
import "lib/geo.al" as geo;
import "lib/money.al" as money;

const SCALE: i64 = 1;

fn describe(n: i64) -> i64 {
    return n * SCALE;
}

fn count_far(ps: list[geo.Point; _]) -> i64 {
    var n: i64 = 0;
    for p in ps {
        if geo.is_far(p) {
            n += 1;
        }
    }
    return n;
}

fn main() {
    let p: geo.Point = geo.Point(x: 3, y: 0);
    let price: money.Point = money.Point(cents: 1999);
    print(geo.describe(p), " | ", money.describe(price), " | ", describe(7));
    print(geo.SCALE, " ", money.SCALE, " ", SCALE, " ", p, " ", price);
    match geo.kind(p) {
        geo.Kind.Origin => { print("origin"); }
        geo.Kind.Axis(d) if d > 2 => { print("far on an axis: ", d); }
        geo.Kind.Axis(_) => { print("on an axis"); }
        geo.Kind.Other => { print("elsewhere"); }
    }
    let k: geo.Kind = geo.Kind.Other;
    print(k == geo.kind(geo.Point(x: 1, y: 1)));
    let ps: list[geo.Point; 4] = [geo.Point(x: 1, y: 1), geo.Point(x: 20, y: 0)];
    var buf: [i64; geo.SCALE] = [0; geo.SCALE];
    buf[0] = count_far(ps);
    print(buf);
}
