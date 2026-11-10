// expect: E0104
// Names are unique across functions, types and constants, even where the syntax would tell
// Color() from Color.Red, or area() from area.
enum Color {
    Red,
}

const area: i64 = 7;

fn Color() -> i64 {
    return 1;
}

fn area() -> i64 {
    return 2;
}

fn main() {
    print(Color(), Color.Red, area(), area);
}
