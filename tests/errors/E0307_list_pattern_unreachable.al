// expect: E0307
fn main() {
    let xs: list[i64; 4] = [1, 2];
    match xs {
        [_, ..] => { print(1); }
        [] => { print(0); }
        [a, b] => { print(a + b); }
    }
}
