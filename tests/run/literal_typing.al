// Literals take their type from context in either operand order, and f32 literals are rounded
// once, straight from their decimal text.
fn main() {
    print(2 + 1.5, " ", 1.5 + 2, " ", min(2, 1.5), " ", 3 < 2.5);
    let b: u8 = 7;
    let bytes: [u8; 3] = [1, b, 2];
    print(bytes, " ", [1, b] == [b, 1], " ", [b, 1] == [1, b]);
    for w in ["a", "bbb", "cc"] {
        print(w, " ", len(w));
    }
    let lists: [list[i64; 4]; 2] = [[1], [2, 3]];
    print(lists, " ", [2.5, 1] == [2.5, 1.0]);
    // 1 + 2^-24 + 1e-32 is just above halfway between two f32 values: it rounds up to 1 + 2^-23.
    // Rounding to f64 first lands exactly on the halfway point, which then rounds down to 1.0.
    let x: f32 = 1.00000005960464477539062500000001;
    let y: f32 = 36028799166447617;
    print(x == 1.0, " ", f64(x) * 100000000.0, " ", y == 36028801313931264.0);
}
