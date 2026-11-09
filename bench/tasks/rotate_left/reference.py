def rotate_left(xs: list[int], k: int) -> list[int]:
    if not xs:
        return []
    k %= len(xs)
    return xs[k:] + xs[:k]
