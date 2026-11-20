// f64(s) caps the exponent it reads, but a str can hold a million digits: the cap must stay beyond what they
// can make up for, or text far outside any f64 would convert to an ordinary value.
fn main() {
    // 0.000...01e10000000, about 10^8999998: too large for an f64.
    var huge: str[1000016] = "0.";
    append(mut huge, repeat('0', 1000000));
    append(mut huge, "1e10000000");
    // 1000...0e-10000000, 10^-9000000: too small.
    var tiny: str[1000016] = "1";
    append(mut tiny, repeat('0', 1000000));
    append(mut tiny, "e-10000000");
    // 1000...0e-1000000 is exactly 1.
    var one: str[1000016] = "1";
    append(mut one, repeat('0', 1000000));
    append(mut one, "e-1000000");
    print(is_float(huge), " ", parse_f64(huge));
    print(is_float(tiny), " ", parse_f64(tiny));
    print(is_float(one), " ", parse_f64(one));
}
