// expect: E0207
fn main() {
    var xs: list[i64; 8] = [3, 1];
    extend(xs, [2]);
    fill(xs, 0);
    rotate(xs, 1);
    print(xs);
}
