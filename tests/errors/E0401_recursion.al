// expect: E0401
fn even(n: u32) -> bool { if n == 0 { return true; } return odd(n - 1); }
fn odd(n: u32) -> bool { if n == 0 { return false; } return even(n - 1); }
fn main() { }
