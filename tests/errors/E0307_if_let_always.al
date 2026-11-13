// expect: E0307
fn main() {
    let pair: (i64, i64) = (1, 2);
    if let (a, b) = pair {
        print(a + b);
    }
}
