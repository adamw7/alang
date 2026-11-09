# Counter.most_common breaks ties by first occurrence, not alphabetically.
import re
from collections import Counter


def top_words(text: str, k: int) -> list[str]:
    return [w for w, _ in Counter(re.findall(r"[a-z]+", text)).most_common(k)]
