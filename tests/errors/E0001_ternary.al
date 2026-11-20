// expect: E0001
fn main() {
    let x: i64 = 3;
    let y: i64 = x > 2 ? 1 : 0;
    print(y);
}
