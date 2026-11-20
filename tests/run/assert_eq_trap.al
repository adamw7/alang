// assert_eq shows both values when they differ.
fn main() {
    let words: list[str[8]; 4] = ["a", "b"];
    assert_eq(len(words), 2);
    assert_eq(words, ["a", "c"]);
}
