// expect: E0203
enum Shape {
    Circle(f64),
    Dot,
}

fn main() {
    print(variant_name(5));
    let s: Option<Shape> = parse_variant("Dot");
    let n: str[8] = str[8](Shape.Dot);
    print(s, n);
}
