// expect: E0216
struct Tagged<T> {
    id: i64,
}

fn main() {
    print(Option.None);
    print(Result.Ok(1));
}
