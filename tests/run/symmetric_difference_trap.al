// A symmetric difference that does not fit in the capacity of the first set traps.
fn main() {
    let a: set[i64; 2] = [1, 2];
    let b: set[i64; 4] = [2, 3, 4];
    print(symmetric_difference(b, a));
    print(symmetric_difference(a, b));
}
