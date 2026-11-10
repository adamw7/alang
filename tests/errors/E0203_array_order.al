// expect: E0203
fn main() {
    let a: [i64; 2] = [1, 2];
    let b: [i64; 2] = [1, 3];
    let first: bool = a < b;
}
