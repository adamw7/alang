// checked_add/sub/mul, rem_euclid, div_euclid and mul_mod on every integer width, at the edges of each.
fn main() {
    print(checked_add(1, 2), " ", checked_add(9223372036854775807, 1), " ", checked_add(-9223372036854775807, -2));
    print(checked_sub(u8(3), 5), " ", checked_sub(u8(5), 3), " ", checked_sub(i8(-100), 28), " ", checked_sub(i8(-100), 29));
    print(checked_mul(i32(65536), 32767), " ", checked_mul(i32(65536), 32768), " ", checked_mul(i32(-65536), 32768));
    print(checked_mul(u64(4294967296), u64(4294967295)), " ", checked_mul(u64(4294967296), u64(4294967296)));
    print(checked_mul(-9223372036854775807 - 1, -1), " ", checked_mul(-9223372036854775807 - 1, 1), " ", checked_mul(0, -1));
    print(checked_add(u16(65535), 0), " ", checked_add(u16(65535), 1), " ", checked_mul(i16(-1), -32768));
    let sum: Option<u32> = checked_add(u32(4000000000), 294967295);
    print(sum);
    if let Option.Some(v) = checked_mul(i64(3), 4) {
        print("product ", v);
    }

    print(rem_euclid(-1, 5), " ", rem_euclid(7, 5), " ", rem_euclid(-7, -2), " ", rem_euclid(7, -2), " ", rem_euclid(0, -3));
    print(div_euclid(-7, 2), " ", div_euclid(-7, -2), " ", div_euclid(7, -2), " ", div_euclid(7, 2), " ", div_euclid(-8, 2));
    print(rem_euclid(i8(-128), i8(-1)), " ", rem_euclid(i8(-128), i8(127)), " ", div_euclid(i8(-128), i8(127)));
    let lo: i64 = -9223372036854775807 - 1;
    print(rem_euclid(lo, lo), " ", rem_euclid(lo + 1, lo), " ", rem_euclid(-1, lo), " ", div_euclid(-1, lo), " ", div_euclid(lo, 3));
    print(rem_euclid(u8(250), u8(7)), " ", div_euclid(u64(18446744073709551615), u64(10)));
    let xs: [i64; 5] = [10, 20, 30, 40, 50];
    for k in -7..=7 step 7 {
        print(k, ": ", xs[rem_euclid(2 + k, 5)]);
    }

    print(mul_mod(9223372036854775807, 9223372036854775807, 1000000007), " ", mul_mod(-3, 4, 5), " ", mul_mod(3, -4, 5));
    print(mul_mod(4611686018427387903, 4611686018427387902, 4611686018427387904), " ", mul_mod(0, 5, 1));
    print(mul_mod(u64(18446744073709551615), u64(18446744073709551615), u64(18446744073709551614)));
    print(mul_mod(u8(255), u8(255), u8(251)), " ", mul_mod(i8(-128), i8(-128), i8(127)), " ", mul_mod(lo, lo, 9223372036854775807));
}

test "rem_euclid and div_euclid go together" {
    for a in -20..=20 {
        for m in [-7, -3, -1, 1, 2, 5] {
            let r: i64 = rem_euclid(a, m);
            assert 0 <= r and r < abs(m);
            assert div_euclid(a, m) * m + r == a;
        }
    }
}

test "rem_euclid by zero traps" traps R0002 {
    rem_euclid(3, 0);
}

test "mul_mod needs a positive modulus" traps R0013 {
    mul_mod(3, 4, -5);
}
