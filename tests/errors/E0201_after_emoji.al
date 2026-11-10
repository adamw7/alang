// expect: E0201
// Columns count code points, as the lexer does. Each emoji before the error used to shift the
// caret and the expression quoted in the hint by one character.
fn main() {
    let s: str[8] = "😀😀"; let n: i32 = len(s);
}
