// Float formatting is exact and identical on every backend:
// rounded to 6 decimals (round-half-even on the exact binary value), trailing zeros removed.
fn main() {
    let zero: f64 = 0.0;
    print(1.0, " ", 1.5, " ", -2.25, " ", 0.1 + 0.2, " ", 1.0 / 3.0, " ", 2.0 / 3.0);
    print(1e20, " ", 1.5e-7, " ", 5e-7, " ", -5e-7, " ", 123456.789);
    print(zero / zero, " ", 1.0 / zero, " ", -1.0 / zero, " ", -zero, " ", zero * -1.0);
    print(0.0078125, " ", 0.0234375, " ", 2.5e-6, " ", 1e15 + 0.3);
    print(sqrt(-1.0), " ", min(zero / zero, 1.0), " ", max(1.0, zero / zero));
    let nan: f64 = zero / zero;
    print(nan == nan, " ", nan != nan, " ", nan < 1.0, " ", -zero == zero);
    let f: f32 = 1.0;
    let third: f32 = f / 3.0;
    print(third, " ", f64(third), " ", third * 3.0 == 1.0);
    print(f32(1e39), " ", f32(-1e39), " ", f32(1e-50));
    print([1.25, -0.5], " ", f64(9007199254740993), " ", f32(1e10));
}
