// expect: E0218
fn shout(s: str[_] = "hey") -> i64 {
    return len(s);
}

fn main() {
    print(shout("a"));
}
