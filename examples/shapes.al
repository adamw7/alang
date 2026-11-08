// Shapes: structs, enums with payloads, exhaustive match and value semantics.

const PI: f64 = 3.141592653589793;

struct Point {
    x: f64,
    y: f64,
}

enum Shape {
    Circle(Point, f64),
    Rect(Point, Point),
    Dot(Point),
}

fn moved(p: Point, dx: f64, dy: f64) -> Point {
    return Point(x: p.x + dx, y: p.y + dy);
}

fn area(s: Shape) -> f64
    ensures result >= 0.0
{
    match s {
        Shape.Circle(_, r) => { return PI * r * r; }
        Shape.Rect(a, b) => { return abs((b.x - a.x) * (b.y - a.y)); }
        Shape.Dot(_) => { return 0.0; }
    }
}

// `mut` parameters are the only references in ALang. They cannot escape the call.
fn translate(s: mut Shape, dx: f64, dy: f64) {
    match s {
        Shape.Circle(c, r) => { s = Shape.Circle(moved(c, dx, dy), r); }
        Shape.Rect(a, b) => { s = Shape.Rect(moved(a, dx, dy), moved(b, dx, dy)); }
        Shape.Dot(p) => { s = Shape.Dot(moved(p, dx, dy)); }
    }
}

fn total_area(shapes: list[Shape; 8]) -> f64 {
    var total: f64 = 0.0;
    for s in shapes {
        total += area(s);
    }
    return total;
}

fn main() {
    let shapes: list[Shape; 8] = [
        Shape.Circle(Point(x: 0.0, y: 0.0), 1.0),
        Shape.Rect(Point(x: 0.0, y: 0.0), Point(x: 2.0, y: 3.0)),
        Shape.Dot(Point(x: 5.0, y: 5.0)),
    ];
    for s in shapes {
        print(s, " area=", area(s));
    }
    print("total area: ", total_area(shapes));

    var first: Shape = shapes[0];
    translate(mut first, 10.0, -1.0);
    print("moved copy: ", first);
    print("original:   ", shapes[0]);
}

test "rectangle area is width times height" {
    let r: Shape = Shape.Rect(Point(x: 1.0, y: 1.0), Point(x: 4.0, y: 3.0));
    assert area(r) == 6.0;
}

test "assignment copies; values are never shared" {
    let a: Shape = Shape.Dot(Point(x: 1.0, y: 2.0));
    var b: Shape = a;
    translate(mut b, 1.0, 1.0);
    assert a == Shape.Dot(Point(x: 1.0, y: 2.0));
    assert b == Shape.Dot(Point(x: 2.0, y: 3.0));
}
