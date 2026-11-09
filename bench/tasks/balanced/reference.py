OPENER = {")": "(", "]": "[", "}": "{"}


def balanced(text: str) -> bool:
    stack = []
    for c in text:
        if c in "([{":
            stack.append(c)
        elif c in OPENER:
            if not stack or stack.pop() != OPENER[c]:
                return False
    return not stack
