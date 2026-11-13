// Slices: xs[a..b], xs[a..], xs[..b], xs[..]. Read in place where they are only read.
struct Item {
    name: str[8],
    qty: i64,
}

fn total(xs: list[i64; _]) -> i64 {
    var sum: i64 = 0;
    for x in xs {
        sum += x;
    }
    return sum;
}

fn main() {
    let s: str[32] = "key=value; other";
    let eq: i64 = index_of(s, '=');
    print(s[..eq], "|", s[eq + 1..10], "|", s[11..], "|", s[..], "|", len(s[3..7]));
    print(s[..3] == "key", " ", starts_with(s[4..], "val"), " ", contains(s[..eq], "y"), " ", s[0..3] < s[4..9]);
    let n: i64 = i64(trim("x 42 y"[1..5]));
    print(n + 1, " ", is_int("12345"[1..3]), " ", count("a,b,c,d"[2..], ','));
    var xs: list[i64; 8] = [5, 1, 4, 2, 3];
    let tail: list[i64; 8] = xs[2..];
    print(tail, " ", total(xs[1..4]), " ", xs[..2] == [5, 1], " ", contains(xs[3..], 4), " ", index_of(xs[1..], 4));
    for i, x in xs[1..3] {
        print(i, ":", x);
    }
    // The body changes xs, so the loop goes through a copy of the slice.
    for x in xs[..3] {
        push(mut xs, x * 10);
    }
    print(xs);
    let arr: [Item; 3] = [Item(name: "a", qty: 1), Item(name: "b", qty: 2), Item(name: "c", qty: 3)];
    let some: list[Item; 3] = arr[1..];
    print(some, " ", len(arr[..0]));
    for w in words("one two three four"[4..]) {
        print(w);
    }
    match s[..3] {
        "key" => { print("matched"); }
        _ => { print("no"); }
    }
}

test "out of range slices trap like slice()" {
    let s: str[8] = "abc";
    assert s[1..] == "bc";
    assert len(s[3..]) == 0;
}
