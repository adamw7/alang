// expect: E0212
// A slice is part of a str, list or array, between integer bounds.
fn main() {
    let n: i64 = 5;
    let xs: list[i64; 4] = [1, 2];
    print(n[1..2], xs[1.5..]);
}
