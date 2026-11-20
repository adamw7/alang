// expect: E0201
fn main(input: str[4096]) {
    for line in split(input, '\n') {
        let parts: Option<(str[64], str[64])> = split_once(line, ',');
        print(parts);
    }
    let r: Result<str[8], str[8]> = Result.Ok("abc");
    let q: Result<str[4], str[8]> = r;
    print(q);
}
