// expect: E0101 E0201 E0202 E0203 E0211 E0212 E0214
// Operators, conversions, literals and assignments with the wrong types; operand_types.err pins every
// message and hint.
fn main() {
    let u: u32 = 1;
    let b: bool = true;
    let s: str[4] = "ab";
    let n: i64 = 2;
    let xs: [i64; 2] = [1, 2];
    let big: list[i64; 4] = [];
    let words: list[str[4]; 4] = [];
    let a1: u32 = -u;
    let a2: i64 = -true;
    let a3: i64 = -nope;
    let a4: i64 = ~b;
    let a5: i64 = ~nope;
    let a6: bool = n and b;
    let a7: bool = s == n;
    let a8: bool = u == n;
    let a9: bool = big == words;
    let a10: bool = [0; 2] == 5;
    let a11: bool = s < s;
    let a12: i64 = s + n;
    let a13: i64 = n + u;
    let a14: f64 = 5.0 % 2.0;
    let a15: i64 = n << nope;
    let a16: i64 = nope + 1;
    let a17: bool = nope < 1;
    let a18: i64 = xs[b];
    let a19: i64 = i64(s);
    let a20: u8 = u8(300);
    let a21: i64 = i64(nope);
    let a22: [i64; 3] = [1, 2];
    let a23: [i64; 1] = [1, 2];
    let a24: list[i64; 1] = [1, 2];
    let a25: bool = n;
    let a26: list[i64; 2] = big;
    let a27: list[i64; 2] = xs;
    let a28: [i64; 2] = [n, b];
    print(2 * (1 + 2.5));
}
