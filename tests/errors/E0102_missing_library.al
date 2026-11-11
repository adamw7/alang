// expect: E0102
// Library functions other languages have, each answered with the ALang way to write it.
fn main() {
    let s: str[16] = "12 ab";
    let n: i64 = parse_int(s);
    let k: i64 = int(2.5);
    splitlines(s);
    strip(s);
    upper(s);
    implode(s);
    reversed(s);
    substring(s);
    isdigit(s);
    sum(s);
    filter(s);
    enumerate(s);
    ord(s);
    chr(s);
    dict();
    input();
    random();
    now();
}
