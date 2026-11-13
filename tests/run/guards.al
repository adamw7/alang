// Match guards and if expressions.
enum Shape {
    Circle(f64),
    Rect(f64, f64),
    Empty,
}

fn describe(s: Shape) -> str[32] {
    var out: str[32] = "";
    match s {
        Shape.Circle(r) if r > 10.0 => { append(mut out, "big circle"); }
        Shape.Circle(r) => { append(mut out, "circle ", r); }
        Shape.Rect(w, h) if w == h => { append(mut out, "square ", w); }
        Shape.Rect(w, h) => { append(mut out, "rect ", w, "x", h); }
        Shape.Empty => { append(mut out, "nothing"); }
    }
    return out;
}

fn grade(score: i64) -> str[8] {
    var g: str[8] = "";
    match score {
        100 => { append(mut g, "perfect"); }
        _ if score >= 90 => { append(mut g, "A"); }
        _ if score >= 75 => { append(mut g, "B"); }
        _ => { append(mut g, "C"); }
    }
    return g;
}

fn sign(x: i64) -> i64 {
    return if x > 0 then 1 else if x < 0 then -1 else 0;
}

/// The longer of two strs, the first on a tie.
fn longer(a: str[_], b: str[_]) -> str[64] {
    return if len(b) > len(a) then str[64](b) else str[64](a);
}

fn main() {
    print(describe(Shape.Circle(12.5)), "; ", describe(Shape.Circle(2.0)), "; ", describe(Shape.Rect(3.0, 3.0)), "; ",
        describe(Shape.Rect(2.0, 5.0)), "; ", describe(Shape.Empty));
    print(grade(100), " ", grade(95), " ", grade(80), " ", grade(10));
    print(sign(-5), " ", sign(0), " ", sign(7));
    let n: u8 = if sign(3) > 0 then 200 else 7;
    let half: f64 = (if n > 100 then 1.0 else 0.0) / 2.0;
    print(n, " ", half);
    let word: str[16] = if n > 100 then "big" else "a little";
    print(word, " ", longer("ab", "abc"), " ", longer("xy", "z"));
    let parts: list[str[8]; 4] = if n > 100 then split("a,b", ",") else words("c d e");
    print(parts);
    let p: Shape = if half > 0.0 then Shape.Rect(1.0, half) else Shape.Empty;
    print(describe(p));
    // Only the branch that is taken runs.
    var xs: list[i64; 2] = [10];
    let first: i64 = if len(xs) > 0 then xs[0] else xs[5];
    print(first);
}

test "a guard falls through to the next arm" {
    assert grade(89) == "B";
    assert describe(Shape.Rect(1.0, 2.0)) == "rect 1.0x2.0";
}
