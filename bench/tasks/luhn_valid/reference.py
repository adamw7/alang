def luhn_valid(number: str) -> bool:
    if len(number) < 2 or not all("0" <= c <= "9" for c in number):
        return False
    total = 0
    for k, c in enumerate(reversed(number)):
        d = ord(c) - ord("0")
        if k % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0
