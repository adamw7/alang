// expect: E0001
fn main() {
    let xs: list[i64; 4] = [3, 0, 2];
    let i: i64 = for i, x in xs {
        if x == 0 {
            break i;
        }
    };
    print(i);
}
