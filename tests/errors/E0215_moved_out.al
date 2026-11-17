// expect: E0215
// A call with `mut` arguments that the statement evaluates first is moved to a let of its own by `alang fix`;
// one that something else is evaluated before, or that runs only sometimes, is not.
fn main() {
    var parts: list[str[8]; 4] = [];
    var stack: list[i64; 8] = [1, 2, 3, 4, 5, 6];
    if not try_split(mut parts, "a,b", ',') {
        return;
    }
    print("top: ", pop(mut stack));
    let n: i64 = 1 + pop(mut stack);
    let k: i64 = len(stack) + pop(mut stack);
    if len(parts) > 0 {
        print(n, k);
    } else if pop(mut stack) > 0 {
        print(k);
    }
    while pop(mut stack) > 0 limit 3 { }
    let ok: bool = len(stack) > 0 and pop(mut stack) > 0;
    print(ok);
}
