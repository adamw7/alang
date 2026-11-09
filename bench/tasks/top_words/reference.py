def top_words(text: str, k: int) -> list[str]:
    counts = {}
    word = ""
    for c in text + " ":
        if "a" <= c <= "z":
            word += c
        elif word:
            counts[word] = counts.get(word, 0) + 1
            word = ""
    ranked = sorted(counts, key=lambda w: (-counts[w], w))
    return ranked[:k]
