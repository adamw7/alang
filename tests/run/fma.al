// a * b + c must round twice (no fused multiply-add), on every compiler and CPU.
fn mul_add(a: f64, b: f64, c: f64) -> f64 {
    return a * b + c;
}

fn main() {
    let r: f64 = mul_add(0.1, 10.0, -1.0);
    print(r == 0.0, " ", r * 1e20);
    let x: f32 = 0.1;
    let y: f32 = x * 10.0 - 1.0;
    print(y == 0.0, " ", f64(y) * 1e10);
}
