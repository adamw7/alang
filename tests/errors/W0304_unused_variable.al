// expect: W0304
enum Shape {
    Circle(f64),
    Rect(f64, f64),
}

fn area(s: Shape) -> f64 {
    let unused: i64 = 42;
    let _kept: i64 = 1;
    match s {
        Shape.Circle(r) => { return 3.0; }
        Shape.Rect(w, h) => { return w * w; }
    }
}

fn main() {
    print(area(Shape.Rect(2.0, 3.0)));
}
