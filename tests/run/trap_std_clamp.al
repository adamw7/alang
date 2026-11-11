fn main() {
    print(clamp(1.5, 0.0, 1.0));
    let lo: f64 = 0.0 / 0.0;
    print(clamp(0.5, lo, 1.0));
}
