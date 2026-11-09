// Forgets that % keeps the sign of the dividend: a negative k gives a negative index, which traps (R0003).
tool fn rotate_left(xs: list[i64; 32], k: i64) -> list[i64; 32] {
    let n: i64 = len(xs);
    var out: list[i64; 32] = [];
    for i in 0..n {
        push(mut out, xs[(i + k) % n]);
    }
    return out;
}
