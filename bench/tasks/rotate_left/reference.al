tool fn rotate_left(xs: list[i64; 32], k: i64) -> list[i64; 32] {
    let n: i64 = len(xs);
    var out: list[i64; 32] = [];
    if n == 0 {
        return out;
    }
    var shift: i64 = k % n;
    if shift < 0 {
        shift += n;
    }
    for i in 0..n {
        push(mut out, xs[(i + shift) % n]);
    }
    return out;
}
