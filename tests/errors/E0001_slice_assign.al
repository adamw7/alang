// expect: E0001
fn main() {
    var xs: list[i64; 4] = [1, 2];
    xs[0..1] = [3];
}
