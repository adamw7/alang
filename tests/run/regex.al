// re_match(s, pattern) asks whether a regular expression matches all of s; re_find(s, pattern) where it first matches.
fn phone(s: str[32]) -> bool {
    return re_match(s, r"\(?\d{3}\)?[ -]?\d{3}-\d{4}");
}

fn first_number(s: str[64]) -> i64 {
    if let Option.Some((a, b)) = re_find(s, r"-?\d+") {
        return i64(s[a..b]);
    }
    return 0;
}

fn main() {
    print(phone("(555) 123-4567"), " ", phone("555-123-4567"), " ", phone("55-123-4567"));
    print(first_number("total: -42 items"), " ", first_number("none"));
    print(re_find("aaa", "a*"), " ", re_find("xaaay", "a+"), " ", re_find("abc", "x?"));
    print(re_find("ab", "a|ab"), " ", re_find("ab", "ab|a"), " ", re_find("caterpillar", "cat(erpillar)?"));
    print(re_match("", ""), " ", re_match("", "a*"), " ", re_match("a", "^a$"), " ", re_match("ba", "^a"));
    print(re_find("one two", "\\w+$"), " ", re_find("x\ny", "x.y"), " ", re_match("x\ny", "x\\sy"));
    print(re_match("aaaa", "a{2,3}"), " ", re_match("aaa", "a{2,3}"), " ", re_match("ab", "[^a-c]b"), " ", re_match("db", "[^a-c]b"));
    print(re_match("a.b", r"a\.b"), " ", re_match("axb", r"a\.b"), " ", re_find("Hello World", "[A-Z][a-z]*$"));
    // A pattern that is a variable is compiled when the call runs: a bad one traps.
    var pattern: str[16] = "(a|b)*c";
    print(re_match("ababc", pattern), " ", re_match("abab", pattern));
    clear(mut pattern);
    append(mut pattern, "(()*)*x");
    print(re_find("yyx", pattern));
    // A group that can only match the empty text compiles to nothing, however deeply it is repeated.
    print(re_match("x", "((((((((){100}){100}){100}){100}){100}){100}){100}){100}x"), " ", re_find("yx", "(()*){0,100}x"));
    print(re_match("ab", "((((((((a){0}){100}){100}){100}){100}){100}){100}){100}ab"), " ", re_match("a", "(a{0}|b)*a"));
}

test "a bad pattern traps" traps R0013 {
    var p: str[8] = "a(b";
    let ok: bool = re_match("ab", p);
    print(ok, len(p));
}
