// expect: E0201
struct Point {
    x: i64,
    y: i64,
}

struct Size {
    x: i64,
    y: i64,
}

fn main() {
    let s: Size = Size(x: 1, y: 2);
    let p: Point = Point(..s, y: 2);
    print(p);
}
