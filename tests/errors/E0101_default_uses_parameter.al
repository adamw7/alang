// expect: E0101
fn area(w: f64, h: f64 = w) -> f64 {
    return w * h;
}

fn main() {
    print(area(2.0));
}
