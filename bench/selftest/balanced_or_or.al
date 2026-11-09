// A habit from C: `||` is a syntax error in ALang (E0001), so the program never runs.
fn opener(c: u8) -> u8 {
    var o: u8 = 0;
    match c {
        ')' => { o = '('; }
        ']' => { o = '['; }
        '}' => { o = '{'; }
        _ => { }
    }
    return o;
}

tool fn balanced(text: str[128]) -> bool {
    var stack: list[u8; 128] = [];
    for c in text {
        if c == '(' || c == '[' || c == '{' {
            push(mut stack, c);
        } else if opener(c) != 0 {
            if len(stack) == 0 {
                return false;
            }
            let top: u8 = pop(mut stack);
            if top != opener(c) {
                return false;
            }
        }
    }
    return len(stack) == 0;
}
