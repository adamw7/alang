// The left and right values of a failed assert keep their bytes too.
fn main() {
    var s: str[4] = "a";
    push(mut s, 200);
    assert s == "a";
}
