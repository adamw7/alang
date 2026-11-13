// expect: E0201 E0207 E0216
// chars builds a list of u32 code points, typed by its variable; push_char adds one code point.
fn main() {
    var s: str[16] = "añ";
    let wrong: list[i64; 4] = chars(s);
    print(chars(s));
    push_char(mut s, "b");
    push_char(s, 98);
    print(wrong);
}
