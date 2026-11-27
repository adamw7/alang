fn main() {
    let xs: list[i64; 4] = [1, 2];
    for w in windows(xs, 1) {
        print(w);
    }
    for c in chunks(xs, 0) {
        print(c);
    }
}
