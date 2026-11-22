// expect: E0201
fn main() {
    let xs: list[i64; 4] = [3, 0, 2];
    let i: i64 = for x in xs {
        if x == 0 {
            break "zero";
        }
    } else -1;
    print(i);
}
