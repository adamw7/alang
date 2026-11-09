const MAX_WORDS: i64 = 128;

fn less(a: str[16], b: str[16]) -> bool {
    for i in 0..min(len(a), len(b)) {
        if a[i] != b[i] {
            return a[i] < b[i];
        }
    }
    return len(a) < len(b);
}

fn count_word(words: mut list[str[16]; MAX_WORDS], counts: mut list[i64; MAX_WORDS], word: str[16]) {
    for i in 0..len(words) {
        if words[i] == word {
            counts[i] += 1;
            return;
        }
    }
    push(mut words, word);
    push(mut counts, 1);
}

tool fn top_words(text: str[256], k: i64) -> list[str[16]; 8] {
    var words: list[str[16]; MAX_WORDS] = [];
    var counts: list[i64; MAX_WORDS] = [];
    var word: str[16] = "";
    for c in text {
        if c >= 'a' and c <= 'z' {
            push(mut word, c);
        } else if len(word) > 0 {
            count_word(mut words, mut counts, word);
            clear(mut word);
        }
    }
    if len(word) > 0 {
        count_word(mut words, mut counts, word);
    }
    var taken: [bool; MAX_WORDS] = [false; MAX_WORDS];
    var top: list[str[16]; 8] = [];
    for r in 0..min(k, len(words)) {
        var best: i64 = -1;
        for i in 0..len(words) {
            if not taken[i] {
                if best < 0 or counts[i] > counts[best] or (counts[i] == counts[best] and less(words[i], words[best])) {
                    best = i;
                }
            }
        }
        taken[best] = true;
        push(mut top, words[best]);
    }
    return top;
}
