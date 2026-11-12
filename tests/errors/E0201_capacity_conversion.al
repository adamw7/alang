// expect: E0201
fn label(s: str[16]) -> str[16] {
    return s;
}

fn main() {
    let big: str[256] = "widget";
    print(label(big));
    let xs: list[i64; 32] = [1, 2];
    let few: list[i64; 4] = xs;
    let lit: str[4] = "toolong";
    print(few, lit);
}
