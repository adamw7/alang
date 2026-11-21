// List patterns: [a, b] matches exactly two elements; [first, ..], [.., last] and [a, .., z] at least those named.
enum Cmd {
    Push(i64),
    Pop,
}

fn describe(xs: list[i64; 8]) -> str[32] {
    return match xs {
        [] => "empty",
        [_] => "one",
        [0, ..] => "starts with zero",
        [a, b] if a == b => "a pair of equals",
        [_, .., 9] => "ends with nine",
        [_, _, ..] => "several",
    };
}

fn sum_ends(a: [i64; 5]) -> i64 {
    match a {
        [first, .., last] => {
            return first + last;
        }
    }
}

fn run(cmds: list[Cmd; 8]) -> list[i64; 8] {
    var stack: list[i64; 8] = [];
    for c in cmds {
        match c {
            Cmd.Push(v) => { push(mut stack, v); }
            Cmd.Pop => {
                if len(stack) > 0 {
                    let _x: i64 = pop(mut stack);
                }
            }
        }
    }
    return stack;
}

fn main() {
    print(describe([]), "; ", describe([7]), "; ", describe([0, 1, 2]), "; ", describe([4, 4]), "; ",
        describe([1, 2, 9]), "; ", describe([1, 2, 3]));
    print(sum_ends([1, 2, 3, 4, 5]));
    let big: [i64; 100000] = [3; 100000];
    match big {
        [a, b, ..] => { print(a + b); }
    }
    match run([Cmd.Push(1), Cmd.Push(2), Cmd.Pop, Cmd.Push(5)]) {
        [bottom, .., top] => { print(bottom, " ", top); }
        [only] => { print(only); }
        [] => { print("none"); }
    }
    match "hi!" {
        ['h', ..] => { print("starts with h"); }
        _ => { print("other"); }
    }
    let pairs: list[(i64, str[4]); 4] = [(1, "a"), (2, "b")];
    if let [(n, s), ..] = pairs {
        print(n, s);
    }
}
