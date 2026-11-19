// str[N](x) of a number or bool, to_base, and parse_i64 with a base.
const ANSWER: str[4] = str[4](42);
const HEX: str[8] = to_base(48879, 16);

fn label(n: i64) -> str[24] {
    return str[24](n);
}

fn main() {
    let a: str[24] = str[24](-9223372036854775808);
    let b: str[8] = str[8](2.5);
    let c: str[5] = str[5](false);
    let d: str[3] = str[3](u8(255));
    print(a, " ", b, " ", c, " ", d, " ", ANSWER, " ", label(7), " ", len(str[24](1000)));
    var s: str[32] = "n=";
    append(mut s, str[8](12), "!");
    print(s, " ", str[16](f32(0.1)), " ", str[24](u64(18446744073709551615)));
    print(to_base(255, 16), " ", to_base(5, 2), " ", to_base(-255, 16), " ", to_base(0, 7), " ", HEX);
    print(to_base(i64(-9223372036854775808), 2), " ", to_base(u64(18446744073709551615), 36), " ", to_base(i8(-128), 16));
    let t: str[2] = pad_left(to_base(u8(10), 16), 2, '0');
    let u: str[16] = to_base(35, 36);
    print(t, " ", u, " [", pad_left(to_base(255, 2), 12, '.'), "]");
    print(parse_i64("ff", 16), " ", parse_i64("FF", 16), " ", parse_i64("-101", 2), " ", parse_i64("+z", 36), " ", parse_i64("12", 10));
    print(parse_i64("7fffffffffffffff", 16), " ", parse_i64("-8000000000000000", 16), " ", parse_i64("8000000000000000", 16));
    print(parse_i64("", 16), " ", parse_i64("-", 2), " ", parse_i64("102", 2), " ", parse_i64("0x1f", 16), " ", parse_i64(" 1", 10));
    print(parse_i64("1zzzzzzzzzzzzzzzzzzzzzz", 36), " ", parse_i64("00000000000000000000000001", 2));
}
