// Integer and float arithmetic across all widths.
fn main() {
    // signed division truncates toward zero; remainder takes the sign of the dividend
    print(7 / 2, " ", -7 / 2, " ", 7 / -2, " ", 7 % 3, " ", -7 % 3, " ", 7 % -3);
    let a: i8 = -128;
    let b: i8 = 127;
    print(a, " ", b, " ", i16(a) * 2, " ", a % -1);
    let big: u64 = 18446744073709551615;
    print(big, " ", big / 3, " ", big % 10, " ", big - 1);
    let h: u64 = 9223372036854775808;
    print(h, " ", h > 1, " ", h >> 63, " ", f64(big), " ", f32(h));
    let m: i64 = -9223372036854775808;
    print(m, " ", m + 1, " ", i64(-1) * 9223372036854775807);
    // bitwise and shifts
    let x: u8 = 0xF0;
    print(x & 0x3C, " ", x | 0x0F, " ", x ^ 0xFF, " ", ~x, " ", x << 1, " ", x >> 4);
    let s: i32 = -16;
    print(s >> 2, " ", s << 2, " ", ~s, " ", 1 << 31);
    let w: u32 = 0xFFFFFFFF;
    print(w, " ", w << 4, " ", w >> 28);
    print(0b1010_1010, " ", 1_000_000, " ", u8('A'), " ", u8('\n'), " ", u8('\x7f'));
    // conversions
    print(i32(3.99), " ", i32(-3.99), " ", u8(255.9), " ", f64(7) / 2.0, " ", i64(true), " ", u8(false));
    print(f32(0.1), " ", f64(f32(0.1)), " ", f32(16777217), " ", i64(f32(16777217)));
    // min / max / abs
    print(min(3, -2), " ", max(u8(3), u8(250)), " ", abs(-5), " ", abs(-2.5), " ", min(1.5, -0.5));
    print(sqrt(2.0), " ", floor(-1.5), " ", ceil(-1.5), " ", floor(2.0), " ", sqrt(f32(2.0)));
}
