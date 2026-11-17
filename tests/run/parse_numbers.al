// parse_i64 and parse_f64 accept exactly what i64(s) and f64(s) convert, and give Option.None for the rest.
fn show(s: str[_]) {
    print("\"", s, "\": ", parse_i64(s), " ", parse_f64(s));
}

fn main() {
    let texts: [str[24]; 14] = ["0", "+17", "-17", "9223372036854775807", "-9223372036854775808", "9223372036854775808",
        "-9223372036854775809", "", "-", " 1", "1.5", "2.5e3", ".25", "1,5"];
    for t in texts {
        show(t);
    }
    var total: i64 = 0;
    for w in words("4 x 5 -1 7.5") {
        match parse_i64(w) {
            Option.Some(n) => { total += n; }
            Option.None => { print("skipped ", w); }
        }
    }
    print(total);
}

test "the extremes of i64" {
    assert parse_i64("-9223372036854775808") == Option.Some(-9223372036854775807 - 1);
    assert parse_i64("9223372036854775808") == Option.None;
    assert parse_f64("19.99") == Option.Some(19.99);
    assert parse_f64("1e400") == Option.None;
}
