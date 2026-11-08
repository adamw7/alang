// expect: E0308
enum S { Circle(f64), Empty }
fn main() { let s: S = S.Empty; match s { S.Circle => { } S.Empty => { } } }
