// expect: E0101 E0201 E0205
fn main() {
    let a: i32 = 1;
    let b: i64 = a;
    a = 2;
    print(missing);
}
