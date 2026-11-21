// expect: E0102
fn double(x: i64) -> i64 {
    return x * 2;
}

fn main() {
    let xs: list[i64; 4] = [1, 2];
    print(map(xs, double));
    print(reduce(xs, 0, double));
    print(argmax(xs));
    print(bisect_left(xs, 1));
}
