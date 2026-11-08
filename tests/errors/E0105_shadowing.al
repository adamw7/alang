// expect: E0105
fn main() { let x: i32 = 1; for i in 0..3 { let x: i32 = 2; } }
