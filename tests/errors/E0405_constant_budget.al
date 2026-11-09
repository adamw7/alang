// expect: E0405
// max-stack: 8388608
// 8 TiB: the checker evaluates constants itself, so it must refuse before building this one.
const BIG: [[i64; 1048576]; 1048576] = [[0; 1048576]; 1048576];

fn main() { print(BIG[0][0]); }
