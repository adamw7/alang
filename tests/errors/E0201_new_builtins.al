// expect: E0201 E0203
// The new builtins check their arguments.
fn shorter(a: str[8], b: str[8]) -> bool {
    return len(a) < len(b);
}

fn main() {
    let o: Option<i64> = Option.Some(1);
    let r: Result<i64, str[8]> = Result.Ok(1);
    var m: map[str[4], i64; 4] = [];
    let a: set[i64; 4] = [1];
    let b: set[i32; 4] = [1];
    var x: i64 = 1;
    var s: str[8] = "";
    print(unwrap_or(m, 0), unwrap_or(o, "zero"), is_some(r), is_ok(o));
    print(union(a, b), intersection(a, [1, 2]), difference(m, a));
    print(min_by([1, 2], shorter), split_once(x, ','), strip_prefix(s, 1.5));
    swap(mut x, mut s);
    let none: bool = assert_eq(x, 1);
    let rep: list[i64; 3] = repeat("ab", 2);
}
