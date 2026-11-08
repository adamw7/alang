fn bad(x: i32) -> i32 ensures result > x { return x; }
fn main() { print(bad(1)); }
