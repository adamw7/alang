// expect: E0201
struct Job {
    retries: i64 = "three",
}

fn main() {
    print(Job());
}
