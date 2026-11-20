// `e?` takes the value out of an Option or a Result, or returns its Option.None or Result.Err from the function.
struct Pair {
    a: i64,
    b: i64,
}

fn parse_pair(s: str[16]) -> Option<Pair> {
    let parts: (str[16], str[16]) = split_once(s, ',')?;
    let a: i64 = parse_i64(trim(parts.0))?;
    // In the middle of an expression, after a call that has already run.
    return Option.Some(Pair(a: a, b: parse_i64(trim(parts.1))? * 10));
}

fn digit(c: u8) -> Result<i64, str[8]> {
    if is_digit(c) {
        return Result.Ok(i64(c - '0'));
    }
    return Result.Err("no digit");
}

// The error widens to the function's str[32].
fn digit_sum(s: str[8]) -> Result<i64, str[32]> {
    var total: i64 = 0;
    for c in s {
        total += digit(c)?;
    }
    return Result.Ok(total);
}

fn positive(n: i64) -> Result<bool, str[32]> {
    if n <= 0 {
        return Result.Err("not positive");
    }
    return Result.Ok(true);
}

// As a statement, for the failure only; through the postcondition when it returns.
fn all_positive(xs: list[i64; 4]) -> Result<i64, str[32]>
    ensures is_ok(result) or len(xs) > 0
{
    for x in xs {
        positive(x)?;
    }
    return Result.Ok(len(xs));
}

// Only when the left side of `and` holds is the right side, with its `?`, evaluated; the same for the branches of if.
fn guarded(flag: bool, s: str[8]) -> Option<bool> {
    let both: bool = flag and parse_i64(s)? > 0;
    let n: i64 = if flag then parse_i64(s)? else 0;
    return Option.Some(both or n > 100);
}

fn first<T>(xs: list[T; _]) -> Option<T> {
    if len(xs) == 0 {
        return Option.None;
    }
    return Option.Some(xs[0]);
}

fn first_twice<T>(xs: list[T; _]) -> Option<(T, T)> {
    let x: T = first(xs)?;
    return Option.Some((x, x));
}

fn pop_last(xs: mut list[str[4]; 4]) -> Option<str[4]> {
    if len(xs) == 0 {
        return Option.None;
    }
    let top: str[4] = pop(mut xs);
    return Option.Some(top);
}

// A call with `mut` arguments may be the whole of what `?` unwraps; the value taken out is a copy.
fn last_two(xs: mut list[str[4]; 4]) -> Option<str[8]> {
    var a: str[8] = pop_last(mut xs)?;
    let b: str[4] = pop_last(mut xs)?;
    append(mut a, b);
    return Option.Some(a);
}

// The condition of a while loop is evaluated before each iteration, its `?` too.
fn count_until_none(xs: list[Option<i64>; 4]) -> Option<i64> {
    var n: i64 = 0;
    var i: i64 = 0;
    while xs[i]? >= 0 limit 4 {
        n += 1;
        i += 1;
    }
    return Option.Some(n);
}

fn main() {
    print(parse_pair("3, 4"), " ", parse_pair("3 4"), " ", parse_pair("x,4"), " ", parse_pair("3,y"));
    print(digit_sum("123"), " ", digit_sum("1a3"));
    print(all_positive([1, 2]), " ", all_positive([1, -2, 3]));
    print(guarded(false, "x"), " ", guarded(true, "x"), " ", guarded(true, "5"), " ", guarded(true, "500"));
    print(first_twice(["a", "b"]), " ", first_twice([1, 2, 3]), " ", first_twice(list[i64; 2]([])));
    var stack: list[str[4]; 4] = ["x", "y", "z"];
    let once: Option<str[8]> = last_two(mut stack);
    print(once, " ", stack);
    let twice: Option<str[8]> = last_two(mut stack);
    print(twice, " ", stack);
    print(count_until_none([Option.Some(1), Option.Some(2), Option.None, Option.Some(3)]), " ",
        count_until_none([Option.Some(1), Option.Some(2), Option.Some(-1)]));
}
