// expect: E0206
fn main() {
    var n: i64 = 0;
    for c in mut "hello" {
        n += i64(c);
    }
    print(n);
}
