// expect: E0206
// An enum variant is a value, not storage; passing it as `mut` used to pass the checker.
enum Color { Red, Blue }
fn paint(c: mut Color) { c = Color.Blue; }
fn main() { paint(mut Color.Red); }
