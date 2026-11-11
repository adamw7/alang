// expect: E0102
fn main() {
    let xs: [i64; 3] = [1, 2, 3];
    let found: bool = includes(xs, 2);
}
