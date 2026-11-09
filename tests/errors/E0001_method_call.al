// expect: E0001
fn main() {
    let xs: [i64; 2] = [1, 2];
    let n: i64 = xs[0].abs();
}
