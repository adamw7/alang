// `if let P = e and cond` and `while let P = e and cond`: the conditions after `and` use the names P binds.
fn first_positive(xs: list[Option<i64>; 8]) -> i64 {
    for x in xs {
        if let Option.Some(v) = x and v > 0 {
            return v;
        }
    }
    return -1;
}

fn describe(o: Option<i64>) -> str[16] {
    if let Option.Some(v) = o and v > 100 and v % 2 == 0 {
        return "big and even";
    } else if let Option.Some(v) = o and v < 0 {
        return "negative";
    } else if is_some(o) {
        return "some";
    }
    return "none";
}

fn main() {
    print(first_positive([Option.None, Option.Some(-3), Option.Some(4), Option.Some(9)]));
    print(describe(Option.Some(102)), " ", describe(Option.Some(101)), " ", describe(Option.Some(-1)), " ",
        describe(Option.None));
    var stack: list[i64; 8] = [1, 2, 30, 4, 5];
    var popped: i64 = 0;
    while let Option.Some(top) = last_key_of(stack) and top < 10 limit 3 {
        let x: i64 = pop(mut stack);
        popped += x;
    }
    print(popped, " ", stack);
    // A failing condition ends the loop without counting as an iteration: two pops fit a limit of 2.
    var again: list[i64; 8] = [30, 4, 5];
    while let Option.Some(top) = last_key_of(again) and top < 10 limit 2 {
        let x: i64 = pop(mut again);
        popped += x;
    }
    print(popped, " ", again);
    let flag: bool = false;
    if let false = (flag and true) {
        print("parenthesized: the whole and");
    }
}

fn last_key_of(xs: list[i64; 8]) -> Option<i64> {
    if len(xs) == 0 {
        return Option.None;
    }
    return Option.Some(xs[len(xs) - 1]);
}
