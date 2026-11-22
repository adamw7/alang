// expect: E0104
struct Point {
    x: i64,
}

type Point = (i64, i64);

fn main() {
    print(Point(x: 1));
}
