// expect: E0204
fn area(w: f64, h: f64 = 1.0) -> f64 {
    return w * h;
}

fn main() {
    print(area());
    print(area(1.0, 2.0, 3.0));
}
