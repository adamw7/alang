// expect: E0401
fn count_down(s: str[_]) -> i64 {
    if len(s) == 0 {
        return 0;
    }
    return 1 + count_down(slice(s, 1, len(s)));
}

fn main() {
    print(count_down("abc"));
}
