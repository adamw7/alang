// expect: E0209
fn three() -> i64 {
    return 3;
}

struct Job {
    retries: i64 = three(),
}

fn main() {
    print(Job());
}
