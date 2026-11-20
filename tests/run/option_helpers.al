// unwrap_or, is_some, is_none, is_ok and is_err on Option and Result values.
fn parse(s: str[8]) -> Result<i64, str[16]> {
    if is_int(s) {
        return Result.Ok(i64(s));
    }
    return Result.Err("not a number");
}

fn main() {
    let some: Option<i64> = parse_i64("42");
    let none: Option<i64> = parse_i64("x");
    print(unwrap_or(some, 0), " ", unwrap_or(none, -1), " ", unwrap_or(parse_i64("7"), 0));
    print(is_some(some), " ", is_none(some), " ", is_some(none), " ", is_none(none));
    let ok: Result<i64, str[16]> = parse("12");
    let err: Result<i64, str[16]> = parse("1x");
    print(unwrap_or(ok, 0), " ", unwrap_or(err, 0), " ", is_ok(ok), " ", is_err(ok), " ", is_ok(err), " ", is_err(err));
    // A str default of a smaller capacity widens; the result is a copy of the payload or the default.
    let name: Option<str[8]> = Option.Some("ann");
    let missing: Option<str[8]> = Option.None;
    var got: str[8] = unwrap_or(name, "?");
    push(mut got, '!');
    print(got, " ", name, " ", unwrap_or(missing, "nobody"));
    var fallback: list[i64; 4] = [1, 2];
    let lists: Option<list[i64; 4]> = Option.None;
    var xs: list[i64; 4] = unwrap_or(lists, fallback);
    push(mut xs, 3);
    print(xs, " ", fallback);
    let counts: map[str[4], i64; 4] = [("a", 1)];
    print(unwrap_or(parse_i64("5"), get_or(counts, "b", 9)));
}
