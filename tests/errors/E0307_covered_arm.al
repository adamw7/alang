// expect: E0307
enum Color {
    Red,
    Green,
    Blue,
}

fn grade(score: i64) -> u8 {
    match score {
        90..=100 => { return 1; }
        95 => { return 2; }
        _ => { return 3; }
    }
}

fn warm(c: Color) -> bool {
    return match c {
        Color.Red | Color.Green => true,
        Color.Green => false,
        Color.Blue => false,
    };
}

fn main() {
    print(grade(91), warm(Color.Red));
}
