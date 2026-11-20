// let P: T = e else { ... };: the names of a pattern that can fail, or a block that leaves.
struct Item {
    name: str[8],
    qty: i64,
}

enum Shape {
    Circle(f64),
    Rect(f64, f64),
}

fn quantity(line: str[16]) -> i64 {
    let Option.Some((name, n)): Option<(str[16], str[16])> = split_once(line, '=') else {
        return -1;
    };
    let Option.Some(q): Option<i64> = parse_i64(trim(n)) else {
        return -2;
    };
    return q + len(name) * 0;
}

fn sum_present(xs: list[Option<i64>; 8]) -> i64 {
    var total: i64 = 0;
    for x in xs {
        let Option.Some(v): Option<i64> = x else {
            continue;
        };
        total += v;
    }
    return total;
}

fn until_negative(xs: list[i64; 8]) -> i64 {
    var n: i64 = 0;
    outer: for x in xs {
        for k in 0..1 {
            let 0..=100: i64 = x + k else {
                break outer;
            };
        }
        n += 1;
    }
    return n;
}

fn width(s: Shape) -> f64 {
    let Shape.Rect(w, _): Shape = s else {
        return 0.0;
    };
    return w;
}

fn stock(items: list[Item; 4], i: i64) -> str[16] {
    var out: str[16] = "";
    let Item(name: n, qty: 1..=9): Item = items[i] else {
        append(mut out, "few or many");
        return out;
    };
    append(mut out, n);
    return out;
}

fn main() {
    print(quantity("apples = 3"), " ", quantity("apples"), " ", quantity("pears = x"));
    print(sum_present([Option.Some(2), Option.None, Option.Some(5)]));
    print(until_negative([5, 50, 500, 1]), " ", until_negative([1, 2]));
    print(width(Shape.Rect(2.0, 3.0)), " ", width(Shape.Circle(1.0)));
    let items: list[Item; 4] = [Item(name: "nut", qty: 3), Item(name: "bolt", qty: 30)];
    print(stock(items, 0), " ", stock(items, 1));
    var bound: Option<i64> = Option.Some(4);
    var Option.Some(m): Option<i64> = bound else {
        return;
    };
    m += 1;
    bound = Option.None;
    print(m, " ", bound);
}
