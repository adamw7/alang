// expect: E0218
fn grow(xs: mut list[i64; 4] = [], n: i64 = 1) {
    push(mut xs, n);
}

fn main() {
    var xs: list[i64; 4] = [];
    grow(mut xs);
    print(xs);
}
