// expect: E0213
struct Job {
    name: str[8],
    retries: i64 = 3,
}

fn main() {
    let j: Job = Job(retries: 1);
    print(j);
}
