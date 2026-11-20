// expect: E0201
fn show(x: i64) {
    print(x);
}

fn half(x: f64) -> f64 {
    return x / 2.0;
}

fn add(acc: i64, x: i64) -> i64 {
    return acc + x;
}

fn main() {
    let xs: list[i64; 4] = [1, 2];
    var words: list[str[4]; 4] = [];
    let long: list[str[8]; 2] = ["abcdefg"];
    print(transform(xs, show));
    print(transform(xs, half));
    print(fold(xs, 0.0, add));
    extend(mut words, long);
    extend(mut words, xs);
    print(lower_bound(xs, "a"));
}
