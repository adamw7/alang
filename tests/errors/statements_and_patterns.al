// expect: E0101 E0104 E0105 E0107 E0201 E0209 E0210 E0212 E0303 E0305 E0308
// Statements, patterns, names and constant sizes used wrongly; statements_and_patterns.err pins every
// message and hint.
const LIMIT: i64 = 4;
const LIMIT: i64 = 5;
const HALF: f64 = 0.5;

enum Color {
    Red,
    Green,
}

enum Mood {
    Happy,
}

fn value() -> i64 {
    return;
}

fn main() {
    let LIMIT: i64 = 1;
    var n: i64 = 0;
    var ys: list[i64; 4] = [];
    let xs: [i64; HALF] = [0; 2];
    let zs: [i64; len(ys)] = [0; 2];
    missing = 1;
    n == 1;
    n + 1;
    n;
    while n < 3 limit 2.5 {
        n += 1;
    }
    for c in 5 {
    }
    for c in nothing {
    }
    match n {
        "a" => { }
        _ => { }
    }
    let c: Color = Color.Red;
    let d: Color = Color;
    match c {
        Mood.Happy => { }
        _ => { }
    }
    match c {
        Color.Blue => { }
        _ => { }
    }
    // Three swapped pairs are too far apart for a "did you mean" hint.
    let badcfe: i64 = 1;
    print(abcdef);
}
