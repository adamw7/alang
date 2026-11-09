tool fn max_drawdown(prices: list[i64; 32]) -> i64 {
    var best: i64 = 0;
    var peak: i64 = -1;
    for p in prices {
        if p > peak {
            peak = p;
        }
        best = max(best, peak - p);
    }
    return best;
}
