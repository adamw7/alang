// A surrogate is not a code point that UTF-8 can encode.
fn main() {
    var s: str[8] = "x";
    push_char(mut s, 0xD800);
    print(s);
}
