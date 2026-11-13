// A slice past the end traps, as slice() does.
fn main() {
    let xs: list[i64; 4] = [1, 2, 3];
    print(xs[1..]);
    print(xs[2..5]);
}
