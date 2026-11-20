// A union that does not fit in the capacity of the first set traps.
fn main() {
    let a: set[i64; 3] = [1, 2, 3];
    let b: set[i64; 3] = [3, 4];
    print(intersection(a, b));
    print(union(a, b));
}
