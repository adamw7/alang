// Declarations from imported files, a file imported twice (directly and through another), and an
// imported tool fn used as a plain function.
import "lib/geometry.al";
import "./lib/units.al";

fn main() {
    let shapes: [Shape; 2] = [Shape.Square(3), Shape.Box(Rect(w: 2, h: 5))];
    for s in shapes {
        print(label(area(s)));
    }
    print(perimeter(Rect(w: 2, h: 5)));
    print(first([7, 8]));
}

test "imported declarations work in tests" {
    assert area(Shape.Square(2)) == 4;
}
