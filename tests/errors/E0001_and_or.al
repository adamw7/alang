// expect: E0001
fn main() {
    let a: bool = true;
    let b: bool = a and a or a;
}
