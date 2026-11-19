// expect: E0213
struct Point {
    x: i64,
    y: i64,
}

fn origin() -> Point {
    return Point(x: 0, y: 0);
}

fn main() {
    let p: Point = Point(..origin(), y: 2);
    print(p);
}
