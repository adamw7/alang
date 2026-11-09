from datetime import date


def days_between(y1: int, m1: int, d1: int, y2: int, m2: int, d2: int) -> int:
    return (date(y2, m2, d2) - date(y1, m1, d1)).days
