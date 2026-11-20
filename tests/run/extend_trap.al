// extend traps before adding anything when the elements do not all fit.
fn main() {
    var xs: list[i64; 4] = [1, 2];
    extend(mut xs, [3, 4]);
    print(xs);
    extend(mut xs, [5]);
    print(xs);
}
