# Forgets the "mod 2^32": Python integers just keep growing, so the result is silently wrong.
def fnv1a_32(text: str) -> int:
    h = 2166136261
    for b in text.encode("utf-8"):
        h ^= b
        h *= 16777619
    return h
