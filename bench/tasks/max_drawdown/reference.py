def max_drawdown(prices: list[int]) -> int:
    best = 0
    peak = None
    for p in prices:
        if peak is None or p > peak:
            peak = p
        best = max(best, peak - p)
    return best
