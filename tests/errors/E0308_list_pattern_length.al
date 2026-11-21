// expect: E0308
fn main() {
    let a: [i64; 3] = [1, 2, 3];
    match a {
        [x, y] => { print(x + y); }
        _ => { }
    }
    let xs: list[i64; 2] = [];
    match xs {
        [_, _, _, ..] => { }
        _ => { }
    }
}
