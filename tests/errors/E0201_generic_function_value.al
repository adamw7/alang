// expect: E0201
fn total(xs: list[i64; _]) -> i64 {
    return sum(xs);
}

fn main() {
    print(total);
}
