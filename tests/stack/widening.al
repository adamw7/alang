// Widening "abcd" to a str[400000] must not need a second copy of the wide value on the stack.
fn main() {
    var s: str[400000] = "abc";
    s = "abcd";
    push(mut s, 'x');
    print(len(s), " ", s);
}
