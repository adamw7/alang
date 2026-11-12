// expect: W0306
fn total(xs: list[i64; _]) -> i64 {
    var sum: i64 = 0;
    for x in xs {
        sum += x;
    }
    return sum;
}

fn main() {
    print("nothing calls total");
}
