tool fn insertion_point(xs: list[i64; 32], target: i64) -> i64 {
    var lo: i64 = 0;
    var hi: i64 = len(xs);
    while lo < hi limit 8 {
        let mid: i64 = lo + (hi - lo) / 2;
        if xs[mid] < target {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return lo;
}
