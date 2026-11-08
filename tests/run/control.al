// if / while / for / match / break / continue.
enum Light {
    Red,
    Yellow,
    Green,
}

fn next(l: Light) -> Light {
    match l {
        Light.Red => { return Light.Green; }
        Light.Green => { return Light.Yellow; }
        Light.Yellow => { return Light.Red; }
    }
}

fn classify(n: i32) -> str[16] {
    match n {
        0 => { return "zero"; }
        1 => { return "one"; }
        -1 => { return "minus one"; }
        _ => {
            if n > 0 {
                return "positive";
            }
            return "negative";
        }
    }
}

fn main() {
    var l: Light = Light.Red;
    for i in 0..4 {
        print(i, ": ", l);
        l = next(l);
    }
    print(classify(0), ", ", classify(1), ", ", classify(-1), ", ", classify(42), ", ", classify(-7));

    var total: i64 = 0;
    for i in 0..10 {
        if i % 2 == 0 {
            continue;
        }
        if i > 7 {
            break;
        }
        total += i;
    }
    print("odd sum below 8: ", total);

    var n: u32 = 27;
    var steps: i32 = 0;
    while n != 1 limit 1000 {
        if n % 2 == 0 {
            n = n / 2;
        } else {
            n = 3 * n + 1;
        }
        steps += 1;
    }
    print("collatz(27) steps: ", steps);

    for a in 0..3 {
        for b in 0..3 {
            if b == a {
                continue;
            }
            if a + b == 3 {
                break;
            }
            print(a, b);
        }
    }

    let flag: bool = steps > 100;
    match flag {
        true => { print("long"); }
        false => { print("short"); }
    }
    for c in u8(250)..u8(253) {
        print(c);
    }
    var empty_runs: i32 = 0;
    for i in 5..5 {
        empty_runs += 1;
    }
    for i in 9..2 {
        empty_runs += 1;
    }
    print("empty ranges ran ", empty_runs, " times");
}
