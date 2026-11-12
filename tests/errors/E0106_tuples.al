// expect: E0106 E0201
// Tuple elements are numbered from 0; let (...) needs a tuple type with one name per element.
fn pair() -> (i64, str[4]) {
    return (1, "a");
}

fn main() {
    let p: (i64, str[4]) = pair();
    print(p.2);
    let (a, b, c): (i64, str[4]) = pair();
    let (x, y): i64 = 5;
    let q: (i64, i64) = (1, "two");
    print(a, b, c, x, y, q);
}
