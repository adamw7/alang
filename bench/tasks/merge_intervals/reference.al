struct Interval {
    start: i64,
    end: i64,
}

tool fn merge_intervals(intervals: list[Interval; 16]) -> list[Interval; 16] {
    var sorted: list[Interval; 16] = intervals;
    let n: i64 = len(sorted);
    for i in 0..n {
        var j: i64 = i;
        while j > 0 and sorted[j - 1].start > sorted[j].start limit 16 {
            let tmp: Interval = sorted[j - 1];
            sorted[j - 1] = sorted[j];
            sorted[j] = tmp;
            j -= 1;
        }
    }
    var merged: list[Interval; 16] = [];
    for iv in sorted {
        let m: i64 = len(merged);
        if m > 0 and iv.start <= merged[m - 1].end {
            merged[m - 1].end = max(merged[m - 1].end, iv.end);
        } else {
            push(mut merged, iv);
        }
    }
    return merged;
}
