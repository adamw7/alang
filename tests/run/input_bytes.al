// The input is bytes, as given: a NUL, a byte that is not UTF-8, no final newline.
fn main(input: str[8]) {
    for b in input {
        print(b);
    }
}
