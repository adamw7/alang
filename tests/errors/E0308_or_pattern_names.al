// expect: E0308
enum Shape {
    Circle(f64),
    Square(f64),
    Point,
}

fn size(s: Shape) -> f64 {
    match s {
        Shape.Circle(r) | Shape.Point => { return r; }
        Shape.Square(w) | Shape.Circle(r) => { return w; }
    }
}

fn main() {
    print(size(Shape.Point));
}
