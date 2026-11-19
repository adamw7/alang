// while let P = e limit n: runs while the pattern matches, with its names bound; the test that ends it is not an iteration.
enum Step {
    Move(i64, i64),
    Stop,
}

struct Cursor {
    at: i64,
}

fn next(c: mut Cursor, xs: list[i64; _]) -> Option<i64> {
    if c.at >= len(xs) {
        return Option.None;
    }
    c.at += 1;
    return Option.Some(xs[c.at - 1]);
}

fn step_of(i: i64) -> Step {
    return if i < 3 then Step.Move(i, i * 2) else Step.Stop;
}

fn main() {
    let xs: list[i64; 4] = [3, 1, 4, 1];
    var stack: list[i64; 4] = xs;
    var total: i64 = 0;
    // Exactly as many iterations as the limit: the final test that does not match does not count.
    while let Option.Some(top) = last(stack) limit 4 {
        total += top;
        let _gone: i64 = pop(mut stack);
    }
    print(total, " ", len(stack));
    var i: i64 = 0;
    while let Step.Move(a, b) = step_of(i) limit 10 {
        print(a, " ", b);
        i += 1;
    }
    var j: i64 = 0;
    outer: while let Option.Some(x) = if j < 9 then Option.Some(j) else Option.None limit 100 {
        j += 1;
        if x % 2 == 0 {
            continue outer;
        }
        if x > 6 {
            break outer;
        }
        print("odd ", x);
    }
    let words: list[str[8]; 4] = ["a", "bb", "ccc"];
    var k: i64 = 0;
    while let (w, true) = (if k < len(words) then words[k] else "", k < len(words)) limit 4 {
        print(w, " ", len(w));
        k += 1;
    }
}

fn last(xs: list[i64; 4]) -> Option<i64> {
    if len(xs) == 0 {
        return Option.None;
    }
    return Option.Some(xs[len(xs) - 1]);
}

test "a cursor runs to the end" {
    var c: Cursor = Cursor(at: 0);
    var seen: i64 = 0;
    let xs: list[i64; 3] = [5, 6, 7];
    while let Option.Some(v) = peek(c, xs) limit 3 {
        seen += v;
        let _n: Option<i64> = next(mut c, xs);
    }
    assert seen == 18;
}

fn peek(c: Cursor, xs: list[i64; _]) -> Option<i64> {
    return if c.at < len(xs) then Option.Some(xs[c.at]) else Option.None;
}
