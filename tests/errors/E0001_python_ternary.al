// expect: E0001
fn main() {
    let x: i64 = 3;
    let sign: i64 = 1 if x > 0 else -1;
    print(sign);
}
