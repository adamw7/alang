// expect: E0218
fn area(w: f64 = 1.0, h: f64) -> f64 {
    return w * h;
}

fn main() {
    print(area(2.0, 3.0));
}
