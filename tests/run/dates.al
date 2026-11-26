// Dates as days since 1970-01-01: days_from_date, date_from_days, weekday and parse_date.
const NAMES: [str[3]; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

fn iso(days: i64) -> str[10] {
    let (y, m, d): (i64, i64, i64) = date_from_days(days);
    var s: str[10] = "";
    append(mut s, pad_left(y, 4, '0'), "-", pad_left(m, 2, '0'), "-", pad_left(d, 2, '0'));
    return s;
}

fn main() {
    print(days_from_date(1970, 1, 1), " ", days_from_date(2000, 3, 1), " ", days_from_date(1, 1, 1), " ", days_from_date(9999, 12, 31));
    print(date_from_days(0), " ", date_from_days(-1), " ", date_from_days(-719162), " ", date_from_days(2932896));
    let a: i64 = days_from_date(2024, 2, 28);
    print(iso(a + 1), " ", iso(a + 2), " ", NAMES[weekday(a) - 1]);
    print(weekday(0), " ", weekday(-1), " ", weekday(-719162), " ", weekday(9223372036854775807));
    print(parse_date("2026-11-26"), " ", parse_date("2026-02-29"), " ", parse_date("2024-02-29"), " ", parse_date("2026-1-01"));
    print(parse_date("0000-01-01"), " ", parse_date("9999-12-31"), " ", parse_date("2026-13-01"), " ", parse_date("20a6-01-01"));
    match parse_date("2026-12-25") {
        Option.Some(xmas) => { print("days to Christmas: ", xmas - days_from_date(2026, 11, 26)); }
        Option.None => { }
    }
    var sum: i64 = 0;
    for z in -719162..=2932896 step 997 {
        let (y, m, d): (i64, i64, i64) = date_from_days(z);
        assert days_from_date(y, m, d) == z;
        sum += y * 400 + m * 31 + d;
    }
    print(sum);
}

test "February in a leap year" {
    assert days_from_date(2024, 3, 1) - days_from_date(2024, 2, 1) == 29;
    assert days_from_date(1900, 3, 1) - days_from_date(1900, 2, 1) == 28;
}

test "a date that does not exist traps" traps R0013 {
    print(days_from_date(2026, 2, 30));
}

test "a day out of range traps" traps R0013 {
    print(date_from_days(2932897));
}
