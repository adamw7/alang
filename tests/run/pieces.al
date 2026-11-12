// for x in split(s, sep) and for x in words(s): the pieces one at a time, each a str of the capacity of s,
// so a piece too long for a list's str type, or more pieces than a list holds, is no trap.

fn longest_line(text: str[64]) -> i64 {
    var best: i64 = 0;
    for line in split(text, '\n') {
        best = max(best, len(line));
    }
    return best;
}

fn first_word_after(text: str[64], marker: str[8]) -> str[64] {
    var seen: bool = false;
    for w in words(text) {
        if seen {
            return w;
        }
        seen = w == marker;
    }
    return "";
}

fn main(input: str[512]) {
    var n: i64 = 0;
    var skipped: i64 = 0;
    for line in split(input, '\n') {
        n += 1;
        if len(line) > 16 {
            skipped += 1;
            continue;
        }
        let short: str[16] = str[16](line);
        print(n, ": [", short, "] ", count(short, ' '), " space(s)");
        if short == "stop" {
            break;
        }
    }
    print("lines read: ", n, ", too long: ", skipped);

    // Empty pieces count, as in split: "", a leading, a trailing and a doubled separator.
    for p in split("", ',') {
        print("only piece: [", p, "]");
    }
    var pieces: str[32] = "";
    for p in split(",a,,b,", ',') {
        append(mut pieces, "<", p, ">");
    }
    print(pieces);
    var multi: str[32] = "";
    for p in split("a::b:c::", "::") {
        append(mut multi, "<", p, ">");
    }
    print(multi);

    // words never makes an empty piece.
    for w in words("   ") {
        print("never: ", w);
    }
    var ws: str[32] = "";
    for w in words("  one\ttwo \n three  ") {
        append(mut ws, w, "|");
    }
    print(ws);

    // The text and the separator are read once: changing them in the loop changes nothing.
    var text: str[16] = "a,b,c";
    var sep: str[1] = ",";
    for p in split(text, sep) {
        clear(mut text);
        clear(mut sep);
        push(mut sep, ';');
        print("p = ", p);
    }
    print("text now [", text, "], sep now [", sep, "]");

    // The text can be any expression.
    for w in words(trim("  x  y  ")) {
        print("w = ", w);
    }

    // Nested, with return and break inside functions.
    var cells: i64 = 0;
    for row in split("1 2 3;4 5;6", ';') {
        for cell in words(row) {
            cells += i64(cell);
        }
    }
    print("cells sum to ", cells);
    print(longest_line("ab\nabcde\n\nabc"));
    print("[", first_word_after("let x = 1", "let"), "] [", first_word_after("nothing here", "let"), "]");
}

test "pieces never trap on their size or number" {
    var n: i64 = 0;
    for w in words("a b c d e f g h i j k l m n o p q r s t u v w x y z") {
        n += len(w);
    }
    assert n == 26;
    assert longest_line("0123456789012345678901234567890123456789") == 40;
}
