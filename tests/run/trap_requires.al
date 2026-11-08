fn half(x: i32) -> i32 requires x % 2 == 0 { return x / 2; }
fn main() { print(half(4)); print(half(5)); }
