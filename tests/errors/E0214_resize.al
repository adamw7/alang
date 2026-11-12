// expect: E0214
fn main() {
    let n: i64 = 42;
    let arr: [i64; 3] = [1, 2, 3];
    let small: list[i32; 3] = [1, 2];
    print(str[8](n));
    print(list[i64; 4](arr));
    print(list[i64; 4](small));
    print(list[i64; 4]("abc"));
}
