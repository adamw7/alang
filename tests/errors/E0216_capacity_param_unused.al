// expect: E0216
fn make<N>(x: i64) -> list[i64; N] {
    var out: list[i64; N] = [];
    push(mut out, x);
    return out;
}

fn main() {
    print(1);
}
