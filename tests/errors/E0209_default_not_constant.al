// expect: E0209
fn now() -> i64 {
    return 1;
}

fn wait(ticks: i64 = now()) -> i64 {
    return ticks;
}

fn main() {
    print(wait());
}
