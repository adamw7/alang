// try_split and try_words fill a list and return false, leaving it empty, when the pieces do not fit.
fn fields(line: str[_]) -> i64 {
    var parts: list[str[8]; 3] = [];
    let ok: bool = try_split(mut parts, line, ',');
    if not ok {
        return -1;
    }
    return len(parts);
}

fn main(input: str[4096]) {
    var good: i64 = 0;
    var bad: i64 = 0;
    for line in split(input, '\n') {
        var parts: list[str[16]; 3] = ["was", "here"];
        let ok: bool = try_split(mut parts, line, ", ");
        if ok and len(parts) == 3 {
            good += 1;
            print(parts[0], " | ", parts[1], " | ", parts[2]);
        } else {
            bad += 1;
            print("skipped (", ok, ", ", len(parts), "): ", line);
        }
    }
    print(good, " good, ", bad, " skipped");
    var ws: list[str[4]; 2] = [];
    let a: bool = try_words(mut ws, "  ab  cd ");
    print(a, " ", ws);
    let b: bool = try_words(mut ws, "ab cd ef");
    print(b, " ", ws);
    let c: bool = try_words(mut ws, "abcde");
    print(c, " ", ws, " ", fields("a,b"), " ", fields("a,b,c,d"), " ", fields("a,toolongfield"), " ", fields(""));
}
