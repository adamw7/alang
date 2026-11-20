// swap checks both positions against the length, not the capacity.
fn main() {
    var xs: list[i64; 4] = [1, 2];
    swap(mut xs, 0, 1);
    print(xs);
    swap(mut xs, 1, 2);
}
