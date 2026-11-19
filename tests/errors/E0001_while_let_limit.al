// expect: E0001
fn main() {
    var xs: list[i64; 4] = [1, 2];
    while let Option.Some(x) = Option.Some(len(xs)) {
        print(x);
    }
}
