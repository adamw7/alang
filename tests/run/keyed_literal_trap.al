// Keys that are not constants are counted as the literal is built: a third different one does not fit.
fn main() {
    let x: i64 = 3;
    print("before");
    let s: set[i64; 2] = [x, 1, 2];
    print(s);
}
