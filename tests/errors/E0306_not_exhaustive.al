// expect: E0306
enum C { A, B }
fn main() { let c: C = C.A; match c { C.A => { } } }
