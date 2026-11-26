// variant_name(e) is the name of an enum value's variant; parse_variant(s) reads one back.
enum Color {
    Red,
    Green,
    Blue,
}

enum Shape {
    Circle(f64),
    Square(f64),
}

fn color_of(s: str[16]) -> Color {
    let o: Option<Color> = parse_variant(trim(s));
    return unwrap_or(o, Color.Red);
}

fn main() {
    for c in variants(Color) {
        print(variant_name(c), " ", pad_left(variant_name(c), 6, '.'));
    }
    let name: str[8] = variant_name(Shape.Square(2.0));
    print(name, " ", len(name));
    var s: str[32] = "shape: ";
    append(mut s, variant_name(Shape.Circle(1.0)));
    print(s);
    let g: Option<Color> = parse_variant("Green");
    let bad: Option<Color> = parse_variant("green");
    let empty: Option<Color> = parse_variant("");
    print(g, " ", bad, " ", empty);
    print(color_of(" Blue "), " ", color_of("Purple"));
}

test "round trip" {
    for c in variants(Color) {
        let n: str[8] = variant_name(c);
        let back: Option<Color> = parse_variant(n);
        assert back == Option.Some(c);
    }
}

test "a name too long for its str traps" traps R0004 {
    let n: str[2] = variant_name(Color.Green);
    print(n);
}
