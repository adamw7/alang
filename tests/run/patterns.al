// Patterns: or-patterns, ranges, nested payloads, tuples, structs, names, if let, let patterns and match expressions.
enum Shape {
    Circle(f64),
    Rect(f64, f64),
    Empty,
}

enum MaybeI64 {
    None,
    Some(i64),
}

struct Point {
    x: i64,
    y: i64,
}

enum Event {
    Click(Point),
    Key(u8, bool),
    Quit,
}

fn kind(day: i64) -> str[8] {
    return match day {
        6 | 7 => "weekend",
        1..=5 => "weekday",
        _ => "?",
    };
}

fn grade(score: u8) -> u8 {
    match score {
        90..=100 => { return 'A'; }
        80..90 => { return 'B'; }
        0..80 => { return 'C'; }
        _ => { return '?'; }
    }
}

fn describe(e: Event) -> str[32] {
    var s: str[32] = "";
    match e {
        Event.Click(Point(x: 0, y: 0)) => { append(mut s, "origin"); }
        Event.Click(Point(x: 0, y: py)) => { append(mut s, "on y axis at ", py); }
        Event.Click(p) if p.x == p.y => { append(mut s, "diagonal ", p.x); }
        Event.Click(Point(x: px, y: py)) => { append(mut s, "click ", px, ",", py); }
        Event.Key('q', _) | Event.Quit => { append(mut s, "quit"); }
        Event.Key(c, true) => { append(mut s, "shift+"); push(mut s, c); }
        Event.Key(c, false) => { push(mut s, c); }
    }
    return s;
}

fn area(s: Shape) -> f64 {
    return match s {
        Shape.Circle(r) => 3.0 * r * r,
        Shape.Rect(w, _h) | Shape.Rect(_h, w) if w > 100.0 => 0.0,
        Shape.Rect(w, h) => w * h,
        Shape.Empty => 0.0,
    };
}

fn first_or(m: MaybeI64, d: i64) -> i64 {
    if let MaybeI64.Some(v) = m {
        return v;
    }
    return d;
}

fn classify(pair: (i64, bool)) -> str[16] {
    match pair {
        (0, _) => { return "zero"; }
        (n, true) if n > 0 => { return "pos on"; }
        (_, true) => { return "on"; }
        (_, false) => { return "off"; }
    }
}

fn label(word: str[8]) -> i64 {
    return match word {
        "a" | "an" | "the" => 0,
        "" => -1,
        w => len(w),
    };
}

fn main() {
    print(kind(6), " ", kind(3), " ", kind(9));
    print(grade(95), " ", grade(85), " ", grade(10), " ", grade(200));
    print(describe(Event.Click(Point(x: 0, y: 0))), "|", describe(Event.Click(Point(x: 0, y: 4))), "|",
        describe(Event.Click(Point(x: 3, y: 3))), "|", describe(Event.Click(Point(x: 1, y: 2))));
    print(describe(Event.Key('q', true)), "|", describe(Event.Quit), "|", describe(Event.Key('x', true)), "|",
        describe(Event.Key('y', false)));
    print(area(Shape.Circle(1.0)), " ", area(Shape.Rect(2.0, 3.0)), " ", area(Shape.Rect(200.0, 3.0)), " ",
        area(Shape.Rect(3.0, 200.0)), " ", area(Shape.Empty));
    print(first_or(MaybeI64.Some(5), 0), " ", first_or(MaybeI64.None, 7));
    print(classify((0, true)), " ", classify((3, true)), " ", classify((-3, true)), " ", classify((4, false)));
    print(label("the"), " ", label(""), " ", label("hello"));
    let (q, r): (i64, i64) = (17 / 5, 17 % 5);
    let Point(x: px, y: _): Point = Point(x: 9, y: 8);
    var (a, Point(x: bx, y: by)): (i64, Point) = (1, Point(x: 2, y: 3));
    a += bx * by;
    print(q, " ", r, " ", px, " ", a);
    let m: MaybeI64 = MaybeI64.Some(-2);
    if let MaybeI64.Some(0) = m {
        print("zero");
    } else if let MaybeI64.Some(n) = m {
        print("some ", n);
    } else {
        print("none");
    }
    let big: u64 = 18446744073709551615;
    let tag: str[4] = match big {
        0..=9 => "low",
        9223372036854775808..=18446744073709551615 => "high",
        _ => "mid",
    };
    print(tag);
}

test "an or-pattern binds from the first alternative that matches, then checks the guard" {
    assert area(Shape.Rect(200.0, 3.0)) == 0.0;
    assert area(Shape.Rect(3.0, 200.0)) == 600.0;
}

test "a name takes a copy" {
    var xs: list[Point; 2] = [Point(x: 1, y: 2)];
    match xs[0] {
        Point(x: 1, y: y0) => {
            xs[0].y = 50;
            assert y0 == 2;
        }
        _ => { }
    }
    assert xs[0].y == 50;
}
