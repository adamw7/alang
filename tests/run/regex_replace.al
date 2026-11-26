// re_replace(s, pattern, to) replaces every match of a regular expression; re_split(s, pattern) splits on them.
fn show(parts: list[str[16]; 16]) {
    var line: str[128] = "";
    for i, p in parts {
        if i > 0 {
            append(mut line, "|");
        }
        append(mut line, p);
    }
    print(len(parts), ": ", line);
}

fn main() {
    let a: str[32] = re_replace("a1b22c333", r"\d+", "#");
    print(a);
    let b: str[32] = re_replace("abxd", "x*", "-");
    print(b);
    let c: str[32] = re_replace("a", "x*|a", "-");
    print(c);
    let d: str[32] = re_replace("  lots   of   space ", " +", ' ');
    print("[", d, "]");
    let e: str[32] = re_replace("2026-11-26", r"^(\d+)-", "Y-");
    print(e);
    let f: str[32] = re_replace("", "x*", "-");
    print(f);
    let g: str[32] = re_replace("abc", "z", "-");
    print(g);
    let p1: list[str[16]; 16] = re_split("a, b;c,,d", r"[,;] *");
    show(p1);
    let p2: list[str[16]; 16] = re_split("abxd", "x*");
    show(p2);
    let p3: list[str[16]; 16] = re_split("", ",");
    show(p3);
    let p4: list[str[16]; 16] = re_split("one  two\tthree", r"\s+");
    show(p4);
    let p5: list[str[16]; 16] = re_split("x1y", r"\d|");
    show(p5);
}

test "a result too long traps" traps R0004 {
    let s: str[4] = re_replace("aaaa", "a", "bb");
    print(s);
}

test "too many pieces trap" traps R0004 {
    let p: list[str[4]; 2] = re_split("a,b,c", ",");
    print(p);
}

test "a bad pattern traps" traps R0013 {
    let pat: str[8] = "(a";
    let s: str[8] = re_replace("a", pat, "b");
    print(s);
}
