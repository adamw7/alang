// Constants are evaluated at compile time and may build any value.
const N: i64 = 4;
const SIZE: i64 = N * N + 1;
const NAME: str[16] = "alang";
const ORIGIN: Point = Point(x: 0, y: 0);
const SQUARE: [Point; 4] = [Point(x: 0, y: 0), Point(x: 1, y: 0), Point(x: 1, y: 1), Point(x: 0, y: 1)];
const MASK: u32 = (1 << 8) - 1;
const DEFAULT_MODE: Mode = Mode.Fast(3);
const COUNT: i64 = len(SQUARE);
const HALF: f64 = 1.0 / 2.0;

struct Point {
    x: i32,
    y: i32,
}

enum Mode {
    Slow,
    Fast(i32),
}

fn main() {
    var buf: [u8; SIZE] = [0; SIZE];
    buf[SIZE - 1] = 1;
    print(N, " ", SIZE, " ", NAME, " ", ORIGIN, " ", MASK, " ", DEFAULT_MODE, " ", COUNT, " ", HALF);
    print(SQUARE[2], " ", len(buf), " ", buf[16]);
    var p: Point = ORIGIN;
    p.x = 5;
    print(p, " ", ORIGIN);
}
