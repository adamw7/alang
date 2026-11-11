// expect: E0102
fn main() {
    let xs: list[i64; 4] = [3, 1, 2];
    let ys: list[i64; 4] = sorted(xs);
}
