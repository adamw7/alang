tool fn levenshtein(a: str[32], b: str[32]) -> i64 {
    let n: i64 = len(a);
    let m: i64 = len(b);
    var prev: [i64; 33] = [0; 33];
    var cur: [i64; 33] = [0; 33];
    for j in 0..m + 1 {
        prev[j] = j;
    }
    for i in 1..n + 1 {
        cur[0] = i;
        for j in 1..m + 1 {
            var cost: i64 = 1;
            if a[i - 1] == b[j - 1] {
                cost = 0;
            }
            cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
        }
        prev = cur;
    }
    return prev[m];
}
