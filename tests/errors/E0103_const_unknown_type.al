// expect: E0103
// A constant with an unknown type gets no value: constants and array sizes computed from it
// must not crash the checker or produce follow-on errors (in either declaration order).
const N: zzz = 4;
const SIZE: i64 = N * N + 1;
const LATER: i64 = M * 2;
const M: yyy = 3;

fn main() {
    var buf: [u8; SIZE] = [0; SIZE];
    var other: [u8; LATER] = [0; LATER];
    buf[0] = 1;
    other[0] = 1;
}
