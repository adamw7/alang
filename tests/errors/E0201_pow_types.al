// expect: E0201 E0203
// pow on floats takes an exponent of the same float type; pow on integers an integer exponent.
fn main() {
    let x: f64 = 2.0;
    let n: i64 = 3;
    let a: f64 = pow(x, n);
    let b: i64 = pow(n, 0.5);
    let c: f32 = pow(f32(2.0), x);
    print(a, b, c);
}
