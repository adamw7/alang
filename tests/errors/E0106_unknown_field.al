// expect: E0106
struct P { x: i32 }
fn main() { let p: P = P(x: 1); print(p.y); }
