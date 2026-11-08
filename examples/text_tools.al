// Text utilities exposed as tools. Run `alang tools text_tools.al` to get
// JSON schemas an agent can use, and `alang call` to invoke them.

fn is_space(c: u8) -> bool {
    return c == ' ' or c == '\n' or c == '\t' or c == '\r';
}

/// Counts the words (runs of non-whitespace bytes) in a text.
tool fn word_count(text: str[1024]) -> i64 {
    var count: i64 = 0;
    var in_word: bool = false;
    for c in text {
        if is_space(c) {
            in_word = false;
        } else if not in_word {
            in_word = true;
            count += 1;
        }
    }
    return count;
}

/// Converts ASCII letters to upper case; other bytes are unchanged.
tool fn upper(text: str[256]) -> str[256] {
    var out: str[256] = "";
    for c in text {
        if c >= 'a' and c <= 'z' {
            push(mut out, c - 32);
        } else {
            push(mut out, c);
        }
    }
    return out;
}

struct Frequency {
    letter: str[1],
    count: i64,
}

/// The most frequent ASCII letter (case-insensitive) and its number of occurrences.
tool fn most_common_letter(text: str[1024]) -> Frequency {
    var counts: [i64; 26] = [0; 26];
    for c in text {
        var lower: u8 = c;
        if c >= 'A' and c <= 'Z' {
            lower = c + 32;
        }
        if lower >= 'a' and lower <= 'z' {
            counts[lower - 'a'] += 1;
        }
    }
    var best: i64 = 0;
    for i in 1..26 {
        if counts[i] > counts[best] {
            best = i;
        }
    }
    var letter: str[1] = "";
    push(mut letter, u8(best) + 'a');
    return Frequency(letter: letter, count: counts[best]);
}

/// True if the text reads the same forwards and backwards, ignoring case and non-letters.
tool fn is_palindrome(text: str[256]) -> bool {
    let up: str[256] = upper(text);
    var letters: str[256] = "";
    for c in up {
        if c >= 'A' and c <= 'Z' {
            push(mut letters, c);
        }
    }
    let n: i64 = len(letters);
    for i in 0..n / 2 {
        if letters[i] != letters[n - 1 - i] {
            return false;
        }
    }
    return true;
}

fn main() {
    let text: str[64] = "The quick brown fox jumps over the lazy dog";
    print("words: ", word_count(text));
    print("upper: ", upper(text));
    print("most common: ", most_common_letter(text));
    print("palindrome? ", is_palindrome("A man, a plan, a canal: Panama"));
}

test "word count handles repeated whitespace" {
    assert word_count("  one  two\tthree\n") == 3;
    assert word_count("") == 0;
}

test "upper leaves non-letters alone" {
    assert upper("abc-123 XyZ") == "ABC-123 XYZ";
}

test "palindromes" {
    assert is_palindrome("Never odd or even");
    assert not is_palindrome("agent");
}
