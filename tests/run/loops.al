// Ranges with ..=, step and rev; loops with an index; labelled break and continue.
fn show(label: str[_], xs: list[i64; _]) {
    print(label, ": ", xs);
}

fn main() {
    var xs: list[i64; 64] = [];
    for i in 0..=5 {
        push(mut xs, i);
    }
    show("0..=5", xs);
    clear(mut xs);
    for i in 0..10 step 3 {
        push(mut xs, i);
    }
    show("0..10 step 3", xs);
    clear(mut xs);
    for i in 0..=9 step 3 {
        push(mut xs, i);
    }
    show("0..=9 step 3", xs);
    clear(mut xs);
    for i in rev 0..5 {
        push(mut xs, i);
    }
    show("rev 0..5", xs);
    clear(mut xs);
    for i in rev 0..10 step 3 {
        push(mut xs, i);
    }
    show("rev 0..10 step 3", xs);
    clear(mut xs);
    for i in rev -3..=3 step 2 {
        push(mut xs, i);
    }
    show("rev -3..=3 step 2", xs);
    clear(mut xs);
    for i in 5..5 {
        push(mut xs, i);
    }
    for i in 5..=4 {
        push(mut xs, i);
    }
    for i in rev 5..5 {
        push(mut xs, i);
    }
    show("empty", xs);
    // At the ends of a type nothing overflows.
    var bytes: str[64] = "";
    for b in u8(250)..=u8(255) {
        append(mut bytes, b, " ");
    }
    for b in rev u8(0)..=u8(3) {
        append(mut bytes, b, " ");
    }
    print(bytes);
    var small: str[64] = "";
    for v in rev i8(-128)..=i8(127) step i8(100) {
        append(mut small, v, " ");
    }
    for v in i8(-128)..=i8(127) step i8(127) {
        append(mut small, v, " ");
    }
    print(small);
    var big: str[128] = "";
    for v in u64(18446744073709551610)..=u64(18446744073709551615) step u64(2) {
        append(mut big, v, " ");
    }
    for v in rev i64(-9223372036854775807)..=i64(9223372036854775807) step i64(9223372036854775807) {
        append(mut big, v, " ");
    }
    print(big);
    // continue still moves to the next value.
    clear(mut xs);
    for i in 0..20 step 4 {
        if i == 8 {
            continue;
        }
        push(mut xs, i);
    }
    show("continue in a step", xs);
    // An index with each element.
    let names: [str[8]; 3] = ["ann", "bob", "cy"];
    for i, name in names {
        print(i, " ", name);
    }
    for i, name in rev names {
        print(i, " ", name);
    }
    for i, c in "hé" {
        print(i, " ", c);
    }
    for i, w in words("one two  three") {
        print(i, " ", w);
    }
    for i, c in chars("hé!") {
        print(i, " ", c);
    }
    var found: str[64] = "";
    let grid: [[i64; 3]; 3] = [[1, 2, 3], [4, 5, 6], [7, 8, 9]];
    outer: for r, row in grid {
        for c, v in row {
            if v % 2 == 0 {
                continue outer;
            }
            if v == 7 {
                break outer;
            }
            append(mut found, r, ",", c, " ");
        }
    }
    print(found);
    var n: i64 = 0;
    var log: str[128] = "";
    rows: while n < 10 limit 10 {
        n += 1;
        cols: for k in 0..10 step 2 {
            if k == 4 {
                continue rows;
            }
            for w in split("a,b,c", ',') {
                if w == "b" {
                    continue cols;
                }
                if n == 3 {
                    break rows;
                }
                append(mut log, n, w, k, " ");
            }
        }
    }
    print(log);
}
