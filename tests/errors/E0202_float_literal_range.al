// expect: E0202
// Float literals that would round to inf are rejected like integer literals out of range.
fn main() {
    let a: f32 = 1e39;
    let b: f64 = -1e400;
    let c: f32 = 1000000000000000000000000000000000000000;
    print(a, b, c);
}
