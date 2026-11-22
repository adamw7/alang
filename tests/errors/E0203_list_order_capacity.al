// expect: E0203
fn main() {
    let a: list[i64; 2] = [1, 2];
    let b: list[i64; 4] = [1, 3];
    print(a < b);
}
