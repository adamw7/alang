// expect: E0201
fn same<N>(a: str[N], b: str[N]) -> i64 {
    return len(a) + len(b);
}

fn main() {
    let a: str[4] = "ab";
    let b: str[8] = "c";
    print(same(a, b));
}
