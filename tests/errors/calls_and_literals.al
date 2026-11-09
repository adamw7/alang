// expect: E0101 E0102 E0103 E0106 E0107 E0201 E0204 E0207 E0213 E0305
// Calls, struct literals, variants and fields used wrongly; calls_and_literals.err pins every message and hint.
struct Point {
    x: i64,
    y: i64,
}

enum Shape {
    Dot,
    Circle(f64),
}

fn grow(xs: mut list[i64; 4], by: i64) {
    push(mut xs, by);
}

fn show(n: i64) {
    print(n);
}

fn main() {
    var small: list[i64; 2] = [];
    var n: i64 = 1;
    grow(small, 1);
    grow(mut small, 1);
    grow(1);
    show(mut n);
    show(n: 1);
    let p: Point = Point(1, 2);
    let q: Point = Pointt(x: 1, y: 2);
    let r: Point = Point(x: 1, z: 2);
    let d: Shape = n.Circle(1.0);
    let e: Shape = Shap.Circle(1.0);
    let f: Shape = Shape.Square(1.0);
    let g: Shape = Shape.Circle(1.0, 2.0);
    let h: i64 = p.z;
    let i: i64 = small.len;
    let j: i64 = n.size;
    let k: i64 = nope.size;
    let same: bool = p == n;
}
