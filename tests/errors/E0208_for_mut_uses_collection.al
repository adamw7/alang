// expect: E0208
fn main() {
    var xs: list[i64; 8] = [1, 2];
    for x in mut xs {
        push(mut xs, x);
    }
    print(xs);
}
