// expect: E0216
// fixed, pad_left and pad_right make text: print or append it, or store it in a typed str.
fn main() {
    let price: f64 = 4.5;
    let width: i64 = len(fixed(price, 2));
    print(width);
}
