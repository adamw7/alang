// expect: E0203
enum Shape {
    Circle(f64),
    Empty,
}

fn main() {
    for s in variants(Shape) {
        print(s);
    }
}
