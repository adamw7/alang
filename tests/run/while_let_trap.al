// A while let that keeps matching past its limit traps.
fn main() {
    var n: i64 = 0;
    while let Option.Some(x) = Option.Some(n) limit 3 {
        n = x + 1;
    }
}
