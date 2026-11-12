// Text is bytes; these read it as UTF-8 code points.
fn show(s: str[_]) {
    var cps: str[256] = "";
    for c in chars(s) {
        append(mut cps, c, " ");
    }
    print(len(s), " bytes, ", char_count(s), " code points, utf8 ", is_utf8(s), ": ", cps);
}

/// The first n code points of s, never cutting one in half.
fn first_chars(s: str[_], n: i64) -> str[64] {
    let k: i64 = min(n, char_count(s));
    return str[64](slice_chars(s, 0, k));
}

fn main() {
    show("");
    show("abc");
    show("héllo wörld");
    show("日本語");
    show("emoji 😀!");
    // Invalid bytes each count as one U+FFFD (65533): a lone continuation byte, a cut-off sequence, an
    // overlong form, a surrogate, and a code point past U+10FFFF.
    show("\x80");
    show("a\xe6\x97");
    show("\xc0\xaf");
    show("\xed\xa0\x80");
    show("\xf4\x90\x80\x80");
    var s: str[32] = "";
    push_char(mut s, 'A');
    push_char(mut s, 233);
    push_char(mut s, u32(0x65e5));
    push_char(mut s, 0x1F600);
    print(s, " ", len(s), " ", char_count(s), " ", is_utf8(s));
    let cps: list[u32; 8] = chars("añ日😀");
    print(cps);
    print(first_chars("日本語のテキスト", 3), "|", first_chars("ab", 5), "|", slice_chars("héllo", 1, 4), "|",
        slice_chars("héllo", 5, 5), "|");
    var count: i64 = 0;
    for c in chars("añ日😀") {
        if c > 127 {
            count += 1;
            continue;
        }
    }
    print(count);
}

test "a code point is never cut" {
    assert first_chars("héllo", 2) == "hé";
    assert char_count("日本") == 2;
    assert len("日本") == 6;
}
