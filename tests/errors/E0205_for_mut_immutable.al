// expect: E0205
fn main() {
    let xs: list[i64; 8] = [1, 2];
    for x in mut xs {
        x += 1;
    }
    print(xs);
}
