// expect: E0201 E0204
// wrapping_* and saturating_* take two integers of the same type.
fn main() {
    let a: u32 = wrapping_mul(u32(3), i64(4));
    let b: f64 = saturating_add(1.5, 2.5);
    let c: i64 = wrapping_add(1);
    print(a, b, c);
}
