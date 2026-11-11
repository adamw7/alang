// Imported by tests/run/imports.al. It imports a file of its own, which imports.al imports too.
import "units.al";

struct Rect {
    w: i64,
    h: i64,
}

enum Shape {
    Square(i64),
    Box(Rect),
}

fn area(s: Shape) -> i64 {
    match s {
        Shape.Square(side) => { return side * side * SCALE; }
        Shape.Box(r) => { return r.w * r.h * SCALE; }
    }
}

/// A tool of this file; to a program that imports it, a plain function.
tool fn perimeter(r: Rect) -> i64 {
    return 2 * (r.w + r.h);
}

// An imported file's main and tests belong to it alone: they are left out of the importing program.
fn main() {
    print("geometry main");
}

test "left out when imported" {
    assert false;
}
