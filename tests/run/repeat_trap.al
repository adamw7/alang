// repeat traps on a count below 0, before it writes anything.
fn main() {
    let n: i64 = -1;
    print("before");
    print("x", repeat("ab", n));
}
