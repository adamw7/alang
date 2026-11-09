# Relies on int(), which also accepts " 1", "+1", "1\n" and non-ASCII digits such as "٤".
def ipv4_to_int(text: str) -> int:
    parts = text.split(".")
    if len(parts) != 4:
        return -1
    value = 0
    for p in parts:
        try:
            n = int(p)
        except ValueError:
            return -1
        if not 0 <= n <= 255 or (len(p) > 1 and p[0] == "0"):
            return -1
        value = value * 256 + n
    return value
