// expect: E0001
// A stray `]` after an index is not a type without its capacity.
fn main() {
    let xs: [i64; 2] = [1, 2];
    let a: i64 = xs[0]];
}
