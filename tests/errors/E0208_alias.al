// expect: E0208
fn main() { var s: str[8] = "a"; append(mut s, s); }
