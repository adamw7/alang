// expect: E0206
enum C { A, B }
fn main() { C.A = C.B; }
