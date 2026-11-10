// Trap messages keep the bytes of str values exactly, also where they are not UTF-8: the
// interpreter used to write U+FFFD for the 0xff byte, where the C backend writes the byte itself.
fn main() {
    var s: str[8] = "bad ";
    push(mut s, 0xff);
    panic(s);
}
