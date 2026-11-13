// The elementary functions give the same bits everywhere: each result is printed exactly, as its 53-bit
// significand and binary exponent, so the C backend must match the interpreter bit for bit.
const PI: f64 = 3.141592653589793;

/// x exactly: "m * 2^e" with m an integer of up to 53 bits, or nan, inf, -inf.
fn exact(x: f64) -> str[48] {
    var out: str[48] = "";
    if is_nan(x) {
        append(mut out, "nan");
        return out;
    }
    if not is_finite(x) {
        append(mut out, x);
        return out;
    }
    if x == 0.0 {
        append(mut out, x);
        return out;
    }
    var m: f64 = abs(x);
    var e: i64 = 0;
    while m >= 2.0 limit 1100 {
        m = m / 2.0;
        e += 1;
    }
    while m < 1.0 limit 1100 {
        m = m * 2.0;
        e -= 1;
    }
    let sig: i64 = i64(m * 4503599627370496.0);
    if x < 0.0 {
        append(mut out, "-");
    }
    append(mut out, sig, "p", e - 52);
    return out;
}

/// The k-th of a spread of arguments: tiny, ordinary, near multiples of pi/2, huge, and some of each sign.
fn arg(k: i64) -> f64 {
    let scales: [f64; 8] = [1e-9, 0.001, 0.5, 1.0, 3.0, 100.0, 1e6, 1e22];
    let seed: i64 = (k * 1103515245 + 12345) % 2147483648;
    let frac: f64 = f64(seed) / 2147483648.0;
    if k % 2 == 1 {
        return -frac * scales[k % 8];
    }
    return frac * scales[k % 8];
}

fn main() {
    let special: [f64; 9] = [0.0, -0.0, 1.0, -1.0, 0.5, PI / 2.0, PI, 1e300, 5e-324];
    for x in special {
        print("x=", exact(x), " exp ", exact(exp(x)), " log ", exact(log(abs(x))), " log2 ", exact(log2(abs(x))),
            " log10 ", exact(log10(abs(x))));
        print("  sin ", exact(sin(x)), " cos ", exact(cos(x)), " tan ", exact(tan(x)), " atan ", exact(atan(x)));
        print("  asin ", exact(asin(clamp(x, -1.0, 1.0))), " acos ", exact(acos(clamp(x, -1.0, 1.0))),
            " atan2 ", exact(atan2(x, -1.0)), " pow ", exact(pow(abs(x), 1.5)), " ", exact(pow(x, 3.0)));
    }
    for k in 0..160 {
        let x: f64 = arg(k);
        print(k, " ", exact(x), ": ", exact(exp(x / 1e4)), " ", exact(log(abs(x))), " ", exact(log2(abs(x))), " ",
            exact(log10(abs(x))), " ", exact(sin(x)), " ", exact(cos(x)), " ", exact(tan(x)), " ", exact(atan(x)), " ",
            exact(asin(x / 1e22)), " ", exact(acos(x / 1e22)), " ", exact(atan2(x, arg(k + 1))), " ",
            exact(pow(abs(x), arg(k + 3) / 1e6)), " ", exact(pow(1.0 + x / 1e22, f64(k * 37))));
    }
    // f32 rounds the f64 result once.
    let a: f32 = 0.7;
    print(sin(a), " ", cos(a), " ", exp(a), " ", log(a), " ", pow(a, f32(2.5)), " ", atan2(a, f32(-2.0)));
    // Exact where they can be.
    print(pow(2.0, 10.0), " ", pow(-2.0, 3.0), " ", log10(1000.0), " ", log2(0.125), " ", exp(0.0), " ", pow(9.0, 0.5));
    print(pow(-8.0, 1.0 / 3.0), " ", pow(0.0, -1.0), " ", pow(-0.0, -3.0), " ", log(0.0), " ", log(-1.0), " ", acos(2.0));
}
