// expect: E0217
fn front(xs: list[i64; _]) -> list[i64; _] {
    return xs;
}

fn main() {
    let xs: list[i64; 4] = [1];
    print(front(xs));
}
