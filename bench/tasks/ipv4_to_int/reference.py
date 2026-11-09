def ipv4_to_int(text: str) -> int:
    parts = text.split(".")
    if len(parts) != 4:
        return -1
    value = 0
    for p in parts:
        if not 1 <= len(p) <= 3 or not all("0" <= c <= "9" for c in p):
            return -1
        if len(p) > 1 and p[0] == "0":
            return -1
        n = int(p)
        if n > 255:
            return -1
        value = value * 256 + n
    return value
