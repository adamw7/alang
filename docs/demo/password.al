enum Strength {
    Weak,
    Fair,
    Strong,
}

struct Report {
    score: i64,
    strength: Strength,
}

/// Rates a password from 0 to 5: one point each for 12+ bytes,
/// a lowercase letter, an uppercase letter, a digit and a symbol.
tool fn rate_password(password: str[64]) -> Report {
    var lower: bool = false;
    var upper: bool = false;
    var digit: bool = false;
    var symbol: bool = false;
    for c in password {
        if c >= 'a' and c <= 'z' {
            lower = true;
        } else if c >= 'A' and c <= 'Z' {
            upper = true;
        } else if c >= '0' and c <= '9' {
            digit = true;
        } else {
            symbol = true;
        }
    }
    var score: i64 = 0;
    for ok in [len(password) >= 12, lower, upper, digit, symbol] {
        if ok {
            score += 1;
        }
    }
    if score >= 4 {
        return Report(score: score, strength: Strength.Strong);
    }
    if score >= 3 {
        return Report(score: score, strength: Strength.Fair);
    }
    return Report(score: score, strength: Strength.Weak);
}

fn main() {
    let r: Report = rate_password("correct-Horse-42");
    print("score: ", r.score);
}

test "short lowercase password is weak" {
    assert rate_password("cat").strength == Strength.Weak;
}

test "long mixed password is strong" {
    assert rate_password("Tr0ub4dor&3-xyz") == Report(score: 5, strength: Strength.Strong);
}
