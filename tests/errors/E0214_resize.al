// expect: E0214
fn main() {
    let arr: [i64; 3] = [1, 2, 3];
    let small: list[i32; 3] = [1, 2];
    print(str[8](arr));
    print(list[i64; 4](arr));
    print(list[i64; 4](small));
    print(list[i64; 4]("abc"));
}
