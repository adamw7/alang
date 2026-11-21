// expect: E0306
fn main() {
    let xs: list[i64; 4] = [1, 2];
    match xs {
        [] => { print(0); }
        [a, ..] if a > 0 => { print(a); }
        [_, _] => { print(2); }
    }
}
