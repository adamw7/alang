// Never makes progress: the deterministic step budget stops every call (R0011).
tool fn max_drawdown(prices: list[i64; 32]) -> i64 {
    var i: i64 = 0;
    while i < 1 limit 1000000000 {
        i = i * 1;
    }
    return 0;
}
