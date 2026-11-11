// The standard library's math: pow, gcd, lcm, clamp, round, trunc, is_nan, is_finite.
const KIB: i64 = pow(2, 10);
const STEP: i64 = gcd(84, 36);
const HALF: f64 = round(0.5);

fn main() {
    print(KIB, " ", STEP, " ", HALF);

    // pow: any integer type for the base, and for the exponent
    print(pow(3, 4), " ", pow(-2, 3), " ", pow(-2, 4), " ", pow(0, 0), " ", pow(0, 5), " ", pow(7, 0));
    let b: u8 = pow(2, 7);
    let e: u64 = 19;
    print(b, " ", pow(u64(10), e), " ", pow(u64(2), 63), " ", pow(i8(-2), 7));
    print(pow(1, 1_000_000_000_000), " ", pow(-1, 1_000_000_000_001), " ", pow(-1, u64(18_446_744_073_709_551_615)));
    print(pow(2, 62), " ", pow(-2, 63), " ", pow(i32(-3), u8(19)));

    // gcd and lcm: never negative
    print(gcd(12, 18), " ", gcd(-12, 18), " ", gcd(12, -18), " ", gcd(0, 0), " ", gcd(0, -5), " ", gcd(17, 5));
    print(lcm(4, 6), " ", lcm(-4, 6), " ", lcm(0, 9), " ", lcm(9, 0), " ", lcm(u8(15), u8(17)), " ", lcm(21, 6));
    print(gcd(i8(-128), i8(64)), " ", gcd(u64(18_446_744_073_709_551_615), u64(5)), " ", lcm(i64(3_037_000_499), i64(3_037_000_493)));

    // clamp: numbers of one type
    print(clamp(5, 0, 10), " ", clamp(-5, 0, 10), " ", clamp(15, 0, 10), " ", clamp(3, 3, 3));
    let c: u8 = clamp(u8(200), 10, 100);
    print(c, " ", clamp(2.5, 0.0, 1.0), " ", clamp(-0.5, 0.0, 1.0), " ", clamp(0.25, 0.0, 1.0));
    let nan: f64 = 0.0 / 0.0;
    print(clamp(nan, 0.0, 1.0), " ", clamp(f32(1.5), f32(0.0), f32(1.0)));
    // At a bound, x itself comes back: the sign of a zero shows which.
    print(clamp(-0.0, 0.0, 1.0), " ", clamp(-0.0, -1.0, 0.0), " ", clamp(0.5, 1.0, 1.0), " ", pow(-1, 2), " ", pow(-1, 0));

    // round halves away from zero; trunc rounds toward zero
    let xs: [f64; 12] = [0.5, 1.5, 2.5, -0.5, -1.5, 2.4999, -2.7, 0.49999999999999994, -0.3, 4503599627370497.0, 1e20, -0.0];
    for x in xs {
        print(x, ": round ", round(x), ", trunc ", trunc(x), ", floor ", floor(x), ", ceil ", ceil(x));
    }
    let inf: f64 = 1.0 / 0.0;
    print(round(inf), " ", trunc(-inf), " ", round(nan), " ", trunc(nan));
    print(round(f32(2.5)), " ", trunc(f32(-2.5)), " ", round(f32(8388607.5)), " ", round(f32(-0.4)));

    print(is_nan(nan), " ", is_nan(1.0), " ", is_nan(inf), " ", is_nan(f32(nan)));
    print(is_finite(1.0), " ", is_finite(inf), " ", is_finite(-inf), " ", is_finite(nan), " ", is_finite(f32(3.0)));
    print(min(2, 3), " ", max(u8(2), 3));
    let small: u8 = min(250, 251);
    print(small);
}

test "pow and gcd agree" {
    for a in 1..20 {
        for n in 0..5 {
            let p: i64 = pow(a, n);
            assert gcd(p, a) == min(p, a) or n == 0;
        }
    }
}
