fn main() {
    var xs: list[i64; 4] = [1, 2];
    let a: i64 = remove(mut xs, 1);
    print(a, " ", xs);
    let b: i64 = remove(mut xs, 1);
    print(b);
}
