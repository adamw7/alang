// Stored in a str, the text must fit its capacity (R0004).
fn main() {
    let ok: str[4] = pad_left(12, 4, '0');
    print(ok);
    let n: i64 = 12345;
    let short: str[4] = pad_left(n, 2, ' ');
    print(short);
}
