fn main() {
    let xs: [i32; 3] = [1, 2, 3];
    let big: u64 = 18446744073709551615;
    print(slice(xs, u64(0), big));
}
