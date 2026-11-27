// index_of from a position, is_hex_digit, is_punct, is_ascii, eq_ignore_case, capitalize and pad_center.
fn main() {
    print(index_of("abab", "b", 2), " ", index_of("abab", 'a', 1), " ", index_of("abab", "b", 4), " ", index_of("abab", "", 4), " ", index_of("ab", "x", u8(0)));
    let xs: list[i64; 8] = [1, 2, 1, 2];
    print(index_of(xs, 2, 2), " ", index_of(xs, 1, 3), " ", index_of([5, 6], 6, 2), " ", index_of(xs, 1));
    var hits: list[i64; 8] = [];
    var at: i64 = index_of("a,b,,c", ',');
    while at >= 0 limit 10 {
        push(mut hits, at);
        at = index_of("a,b,,c", ',', at + 1);
    }
    print(hits);
    var classes: str[64] = "";
    for c in "aF9g !~ é" {
        append(mut classes, if is_hex_digit(c) then "h" else "-", if is_punct(c) then "p" else "-", if is_ascii(c) then "a" else "-", " ");
    }
    print(classes);
    print(is_ascii("plain text"), " ", is_ascii("café"), " ", is_ascii(""), " ", is_punct('_'), " ", is_punct(' '), " ", is_hex_digit('G'));
    print(eq_ignore_case("Yes", "YES"), " ", eq_ignore_case("yes", "no!"), " ", eq_ignore_case("", ""), " ", eq_ignore_case("a", "ab"), " ", eq_ignore_case("[", "{"));
    print(capitalize("hELLO wORLD"), " ", capitalize(""), " ", capitalize("1abc"), " ", capitalize("é"), " ", capitalize("x"));
    let cap: str[16] = capitalize("ann");
    print(cap, " ", cap(cap));
    print("[", pad_center("ab", 5, '*'), "] [", pad_center("ab", 6, '*'), "] [", pad_center("abc", 2, '*'), "] [", pad_center(42, 6, '0'), "]");
    print("[", pad_center(-7, 4, '0'), "] [", pad_center(fixed(1.5, 1), 7, ' '), "] [", pad_center(true, 8, '.'), "] [", pad_center("", -1, 'x'), "]");
    let title: str[20] = pad_center("menu", 10, '=');
    print(title, " ", len(title));
    var line: str[32] = "";
    append(mut line, "|", pad_center(group_digits(1234, ','), 9, ' '), "|");
    print(line);
}
