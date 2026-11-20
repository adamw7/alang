// expect: E0208
// Two elements of one list are exchanged by position: with i == j they would be the same element.
fn main() {
    var xs: list[i64; 4] = [1, 2];
    swap(mut xs[0], mut xs[1]);
    print(xs);
}
