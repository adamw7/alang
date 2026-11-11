// Text utilities exposed as tools, built on the standard library's text and character functions.
// Run `alang tools text_tools.al` to get JSON schemas an agent can use, and `alang call` to invoke them.

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
    return to_upper(text);
}

struct Frequency {
    letter: str[1],
    count: i64,
}

/// The most frequent ASCII letter (case-insensitive) and its number of occurrences.
tool fn most_common_letter(text: str[1024]) -> Frequency {
    var counts: [i64; 26] = [0; 26];
    for c in to_lower(text) {
        if is_lower(c) {
            counts[c - 'a'] += 1;
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
    var letters: str[256] = "";
    for c in to_upper(text) {
        if is_alpha(c) {
            push(mut letters, c);
        }
    }
    var backwards: str[256] = letters;
    reverse(mut backwards);
    return letters == backwards;
}

/// The words of a text, without repeats, in alphabetical order (at most 32 words of up to 24 bytes).
tool fn vocabulary(text: str[1024]) -> list[str[24]; 32] {
    var all: list[str[24]; 32] = words(to_lower(text));
    sort(mut all);
    var unique: list[str[24]; 32] = [];
    for w in all {
        if not contains(unique, w) {
            push(mut unique, w);
        }
    }
    return unique;
}

fn main() {
    let text: str[64] = "The quick brown fox jumps over the lazy dog";
    print("words: ", word_count(text));
    print("upper: ", upper(text));
    print("most common: ", most_common_letter(text));
    print("palindrome? ", is_palindrome("A man, a plan, a canal: Panama"));
    let vocab: str[256] = join(vocabulary("the cat and the hat"), " ");
    print("vocabulary: ", vocab);
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

test "vocabulary sorts and drops repeats" {
    assert vocabulary("b a B c a") == ["a", "b", "c"];
    assert len(vocabulary("   ")) == 0;
}
