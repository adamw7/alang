UNITS = {"h": (0, 3600), "m": (1, 60), "s": (2, 1)}


def parse_duration(text: str) -> int:
    if not text:
        return -1
    total = 0
    last = -1
    i = 0
    while i < len(text):
        j = i
        while j < len(text) and "0" <= text[j] <= "9":
            j += 1
        if not 1 <= j - i <= 6 or j >= len(text) or text[j] not in UNITS:
            return -1
        rank, scale = UNITS[text[j]]
        if rank <= last:
            return -1
        last = rank
        total += int(text[i:j]) * scale
        i = j + 1
    return total
