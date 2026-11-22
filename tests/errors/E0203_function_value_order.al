// expect: E0203
fn inc(x: i64) -> i64 {
    return x + 1;
}

fn main() {
    var fs: list[fn(i64) -> i64; 4] = [inc, inc];
    sort(mut fs);
    let m: map[fn(i64) -> i64, i64; 4] = [];
    print(fs, m);
}
