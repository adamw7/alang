// A reverse Polish notation calculator, exposed to agents as a tool.
// Shows fixed-capacity collections (no heap), byte-level parsing and
// error handling with enums instead of exceptions.

const MAX_TOKENS: i64 = 32;
const STACK_CAP: i64 = 16;
const MAX_LEN: i64 = 128;
const I64_MIN: i64 = -9223372036854775808;
const I64_MAX: i64 = 9223372036854775807;

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
    // A result outside the i64 range: checked before computing it, which would trap.
    Overflow,
    Leftover(i64),
}

/// Splits `text` into tokens. Returns -1 on success, otherwise the byte offset of the first
/// token that could not be read: an unknown character, a number too large for an i64, or one
/// token more than fits.
fn tokenize(text: str[MAX_LEN], tokens: mut list[Token; MAX_TOKENS]) -> i64 {
    var i: i64 = 0;
    // Every iteration consumes at least one byte.
    while i < len(text) limit MAX_LEN {
        let c: u8 = text[i];
        if c == ' ' {
            i += 1;
        } else if c >= '0' and c <= '9' {
            let start: i64 = i;
            var n: i64 = 0;
            // A number may have leading zeros, so its length is bounded only by the text.
            while i < len(text) and text[i] >= '0' and text[i] <= '9' limit MAX_LEN {
                let digit: i64 = i64(text[i] - '0');
                // n * 10 + digit would overflow.
                if n > (I64_MAX - digit) / 10 {
                    return start;
                }
                n = n * 10 + digit;
                i += 1;
            }
            if len(tokens) == cap(tokens) {
                return start;
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

// Whether a * b fits an i64. Each division below rounds toward zero and cannot overflow itself.
fn product_fits(a: i64, b: i64) -> bool {
    if a > 0 {
        if b > 0 {
            return a <= I64_MAX / b;
        }
        return b >= I64_MIN / a;
    }
    if b > 0 {
        return a >= I64_MIN / b;
    }
    return a == 0 or b >= I64_MAX / a;
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
                // ALang traps on overflow, so each operation checks its range first.
                match tok {
                    Token.Plus => {
                        if (b > 0 and a > I64_MAX - b) or (b < 0 and a < I64_MIN - b) {
                            return Outcome.Overflow;
                        }
                        r = a + b;
                    }
                    Token.Minus => {
                        if (b < 0 and a > I64_MAX + b) or (b > 0 and a < I64_MIN + b) {
                            return Outcome.Overflow;
                        }
                        r = a - b;
                    }
                    Token.Times => {
                        if not product_fits(a, b) {
                            return Outcome.Overflow;
                        }
                        r = a * b;
                    }
                    Token.Div => {
                        if b == 0 {
                            return Outcome.DivisionByZero;
                        }
                        if a == I64_MIN and b == -1 {
                            return Outcome.Overflow;
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
/// Supports + - * / on 64-bit integers; a result outside their range is Overflow.
tool fn calc(expr: str[MAX_LEN]) -> Outcome {
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

test "numbers use the whole i64 range, leading zeros included" {
    assert calc("9223372036854775807") == Outcome.Value(I64_MAX);
    assert calc("00000000000000000000000042") == Outcome.Value(42);
    assert calc("1 9223372036854775808 +") == Outcome.BadInput(2);
    assert calc("99999999999999999999") == Outcome.BadInput(0);
}

test "results outside the i64 range are reported, not trapped" {
    assert calc("9223372036854775807 1 +") == Outcome.Overflow;
    assert calc("0 9223372036854775807 - 2 -") == Outcome.Overflow;
    assert calc("0 9223372036854775807 - 1 -") == Outcome.Value(I64_MIN);
    assert calc("3037000500 3037000500 *") == Outcome.Overflow;
    assert calc("3037000499 3037000499 *") == Outcome.Value(9223372030926249001);
    assert calc("0 4611686018427387904 - 2 *") == Outcome.Value(I64_MIN);
    assert calc("0 4611686018427387904 - 0 2 - *") == Outcome.Overflow;
    assert calc("0 9223372036854775807 - 1 - 0 1 - /") == Outcome.Overflow;
    assert calc("0 9223372036854775807 - 1 - 0 1 - *") == Outcome.Overflow;
}

test "a token past the capacity is reported where it starts" {
    var text: str[MAX_LEN] = "";
    for k in 0..MAX_TOKENS + 1 {
        append(mut text, "1 ");
    }
    assert calc(text) == Outcome.BadInput(2 * MAX_TOKENS);
}
