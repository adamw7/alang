# Right logic, wrong parameter name: the tool is called as balanced(text=...).
def balanced(s: str) -> bool:
    stack = []
    for c in s:
        if c in "([{":
            stack.append(c)
        elif c in ")]}":
            if not stack or "([{"[")]}".index(c)] != stack.pop():
                return False
    return not stack
