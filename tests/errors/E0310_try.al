// expect: E0310
// `?` needs an Option or a Result, and a function that returns the same kind for it to return.
fn not_fallible(n: i64) -> Option<i64> {
    let v: i64 = n?;
    return Option.Some(v);
}

fn plain(s: str[8]) -> i64 {
    return parse_i64(s)?;
}

fn checked(s: str[8]) -> Result<i64, str[8]> {
    let n: i64 = parse_i64(s)?;
    return Result.Ok(n);
}

fn narrow(r: Result<i64, str[32]>) -> Result<i64, str[8]> {
    return Result.Ok(r?);
}

fn positive(s: str[8]) -> Option<i64>
    requires parse_i64(s)? > 0
{
    return parse_i64(s);
}

fn main() {
    let n: i64 = parse_i64("1")?;
    print(not_fallible(1), plain("1"), checked("1"), narrow(Result.Ok(1)), positive("1"), n);
}

test "no function to return from" {
    assert parse_i64("1")? == 1;
}
