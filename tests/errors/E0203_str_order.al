// expect: E0203
// A str is ordered against another str (byte by byte, as sort orders them), not against one character.
fn main() {
    let a: str[8] = "apple";
    let first: bool = a < 'b';
    print(first);
}
