// expect: E0307
// A pattern that always matches leaves nothing for the else block to do.
fn main() {
    let pair: (i64, i64) = (1, 2);
    let (a, b): (i64, i64) = pair else {
        return;
    };
    let n: i64 = a + b else {
        return;
    };
    print(n);
}
