// count_ones, leading_zeros, trailing_zeros, rotl and rotr, over the bits of each type.
const POPCOUNT: i64 = count_ones(u16(0xFFFF));

fn main() {
    print(count_ones(u8(7)), " ", count_ones(i8(-1)), " ", count_ones(0), " ", count_ones(u64(18446744073709551615)));
    print(count_ones(i64(-9223372036854775808)), " ", count_ones(i32(-2)), " ", POPCOUNT);
    print(leading_zeros(u8(1)), " ", leading_zeros(u8(0)), " ", leading_zeros(i8(-1)), " ", leading_zeros(1));
    print(leading_zeros(u64(1)), " ", leading_zeros(u32(0x80000000)), " ", leading_zeros(i16(0)));
    print(trailing_zeros(u8(8)), " ", trailing_zeros(u8(0)), " ", trailing_zeros(i64(0)), " ", trailing_zeros(i32(-2147483648)));
    print(rotl(u8(0x81), 1), " ", rotr(u8(0x81), 1), " ", rotl(u8(0x81), 9), " ", rotl(u8(0x81), -1));
    print(rotl(i8(-128), 1), " ", rotr(i8(1), 1), " ", rotl(i8(64), 1), " ", rotr(i16(1), 0));
    print(rotl(u64(1), 63), " ", rotr(u64(1), 1), " ", rotl(i64(-1), 17), " ", rotl(u32(0x12345678), 8));
    let n: u8 = 200;
    print(rotl(u32(1), n), " ", rotr(u16(1), i64(-3)));
}
