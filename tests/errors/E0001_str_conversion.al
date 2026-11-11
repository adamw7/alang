// expect: E0001
fn main() {
    var s: str[16] = "n = ";
    append(mut s, str(42));
}
