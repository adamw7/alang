// expect: E0001
fn main() {
    let n: i64 = 5;
    let parity: i64 = if n % 2 == 0 { 0 } else { 1 };
}
