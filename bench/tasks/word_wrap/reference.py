def word_wrap(text: str, width: int) -> list[str]:
    lines = []
    line = ""
    for word in text.split(" ") if text else []:
        if not line:
            line = word
        elif len(line) + 1 + len(word) <= width:
            line += " " + word
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines
