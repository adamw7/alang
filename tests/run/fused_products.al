// A product is rounded before it is added, in every backend: a C compiler must not fuse a * b + c into one
// multiply-add, which rounds once. The numbers come from the input, so that no compiler can fold them first,
// and each case has its own, so that no product is shared.
fn main(input: str[64]) {
    var n: i64 = 0;
    for w in words(input) {
        if len(w) <= 16 and is_int(str[16](w)) {
            n = i64(str[16](w));
        }
    }
    // (1 + d)(1 - d) is 1 - d^2, which rounds to 1 for d = 2^-30 and less; fused with - 1, it would be -d^2.
    var d: [f64; 7] = [0.0; 7];
    d[0] = 1.0 / f64(n);
    for i in 1..7 {
        d[i] = d[i - 1] / 2.0;
    }
    let c: f64 = -1.0;
    var acc: f64 = c;
    acc += (1.0 + d[5]) * (1.0 - d[5]);
    print((1.0 + d[0]) * (1.0 - d[0]) + c == 0.0, " ", c + (1.0 + d[1]) * (1.0 - d[1]) == 0.0, " ",
        (1.0 + d[2]) * (1.0 - d[2]) - 1.0 == 0.0, " ", 1.0 - (1.0 + d[3]) * (1.0 - d[3]) == 0.0, " ",
        -((1.0 + d[4]) * (1.0 - d[4])) + 1.0 == 0.0, " ", acc == 0.0);
    // The same in f32, with 2^-13.
    let e: f32 = f32(1.0) / f32(n / 131072);
    let a32: f32 = f32(1.0) + e;
    let b32: f32 = f32(1.0) - e;
    let a33: f32 = f32(1.0) + e / f32(2.0);
    let b33: f32 = f32(1.0) - e / f32(2.0);
    print(a32 * b32 - f32(1.0) == f32(0.0), " ", f32(1.0) - a33 * b33 == f32(0.0));
    // A difference of products: exact here, 4 d.
    let a: f64 = 1.0 + d[6];
    let b: f64 = 1.0 - d[6];
    print(a * a - b * b == 4.0 * d[6]);
}
