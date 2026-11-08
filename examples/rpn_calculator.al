// A reverse Polish notation calculator, exposed to agents as a tool.
// Shows fixed-capacity collections (no heap), byte-level parsing and
// error handling with enums instead of exceptions.

const MAX_TOKENS: i64 = 32;
const STACK_CAP: i64 = 16;

enum Token {
    Num(i64),
    Plus,
    Minus,
    Times,
    Div,
}

enum Outcome {
    Value(i64),
    BadInput(i64),
    StackUnderflow,
    StackOverflow,
    DivisionByZero,
    Leftover(i64),
}

/// Splits `text` into tokens. Returns -1 on success, otherwise the byte offset
/// of the first character that could not be tokenized.
fn tokenize(text: str[128], tokens: mut list[Token; MAX_TOKENS]) -> i64 {
    var i: i64 = 0;
    while i < len(text) limit 128 {
        let c: u8 = text[i];
        if c == ' ' {
            i += 1;
        } else if c >= '0' and c <= '9' {
            var n: i64 = 0;
            while i < len(text) and text[i] >= '0' and text[i] <= '9' limit 19 {
                n = n * 10 + i64(text[i] - '0');
                i += 1;
            }
            if len(tokens) == cap(tokens) {
                return i;
            }
            push(mut tokens, Token.Num(n));
        } else {
            var t: Token = Token.Plus;
            match c {
                '+' => { t = Token.Plus; }
                '-' => { t = Token.Minus; }
                '*' => { t = Token.Times; }
                '/' => { t = Token.Div; }
                _ => { return i; }
            }
            if len(tokens) == cap(tokens) {
                return i;
            }
            push(mut tokens, t);
            i += 1;
        }
    }
    return -1;
}

fn evaluate(tokens: list[Token; MAX_TOKENS]) -> Outcome {
    var stack: list[i64; STACK_CAP] = [];
    for tok in tokens {
        match tok {
            Token.Num(n) => {
                if len(stack) == cap(stack) {
                    return Outcome.StackOverflow;
                }
                push(mut stack, n);
            }
            _ => {
                if len(stack) < 2 {
                    return Outcome.StackUnderflow;
                }
                let b: i64 = pop(mut stack);
                let a: i64 = pop(mut stack);
                var r: i64 = 0;
                match tok {
                    Token.Plus => { r = a + b; }
                    Token.Minus => { r = a - b; }
                    Token.Times => { r = a * b; }
                    Token.Div => {
                        if b == 0 {
                            return Outcome.DivisionByZero;
                        }
                        r = a / b;
                    }
                    Token.Num(_) => { }
                }
                push(mut stack, r);
            }
        }
    }
    if len(stack) != 1 {
        return Outcome.Leftover(len(stack));
    }
    return Outcome.Value(stack[0]);
}

/// Evaluates an integer expression in reverse Polish notation, e.g. "3 4 + 2 *".
/// Supports + - * / on 64-bit integers.
tool fn calc(expr: str[128]) -> Outcome {
    var tokens: list[Token; MAX_TOKENS] = [];
    let bad: i64 = tokenize(expr, mut tokens);
    if bad >= 0 {
        return Outcome.BadInput(bad);
    }
    return evaluate(tokens);
}

fn main() {
    let inputs: [str[32]; 5] = ["3 4 + 2 *", "100 7 /", "1 2 3", "5 0 /", "2 x"];
    for text in inputs {
        print(text, " => ", calc(text));
    }
}

test "evaluates nested expressions" {
    assert calc("3 4 + 2 *") == Outcome.Value(14);
    assert calc("10 2 8 * + 3 -") == Outcome.Value(23);
}

test "reports errors as values" {
    assert calc("1 +") == Outcome.StackUnderflow;
    assert calc("4 0 /") == Outcome.DivisionByZero;
    assert calc("1 2") == Outcome.Leftover(2);
    assert calc("1 ?") == Outcome.BadInput(2);
}
