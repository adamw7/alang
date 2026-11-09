tool fn word_wrap(text: str[256], width: i64) -> list[str[256]; 128] {
    let n: i64 = len(text);
    var lines: list[str[256]; 128] = [];
    var line: str[256] = "";
    var word: str[256] = "";
    for i in 0..n + 1 {
        if i < n and text[i] != ' ' {
            push(mut word, text[i]);
        } else if len(word) > 0 {
            if len(line) == 0 {
                line = word;
            } else if len(line) + 1 + len(word) <= width {
                push(mut line, ' ');
                append(mut line, word);
            } else {
                push(mut lines, line);
                line = word;
            }
            clear(mut word);
        }
    }
    if len(line) > 0 {
        push(mut lines, line);
    }
    return lines;
}
