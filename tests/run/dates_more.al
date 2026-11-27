// is_leap_year, days_in_month, add_months, format_date, parse_datetime and format_datetime.
fn main() {
    print(is_leap_year(2024), " ", is_leap_year(1900), " ", is_leap_year(2000), " ", is_leap_year(2026), " ", is_leap_year(-4));
    print(days_in_month(2024, 2), " ", days_in_month(2026, 2), " ", days_in_month(2026, 4), " ", days_in_month(2026, 12));
    let jan31: i64 = days_from_date(2026, 1, 31);
    print(format_date(add_months(jan31, 1)), " ", format_date(add_months(jan31, -2)), " ", format_date(add_months(jan31, 13)));
    print(format_date(add_months(days_from_date(2024, 2, 29), 12)), " ", format_date(add_months(jan31, 0)), " ", format_date(add_months(jan31, -24300)));
    print(format_date(0), " ", format_date(-1), " ", format_date(-719162), " ", format_date(2932896), " ", len(format_date(5)));
    let d: str[16] = format_date(days_from_date(2026, 11, 27));
    print(d, " ", parse_date(d) == Option.Some(days_from_date(2026, 11, 27)));
    print(parse_datetime("1970-01-01T00:00:00Z"), " ", parse_datetime("2026-11-27T10:30:00+01:00"), " ", parse_datetime("2026-11-27 10:30"));
    print(parse_datetime("2026-11-27T10:30:59.999Z"), " ", parse_datetime("1969-12-31T23:59:59Z"), " ", parse_datetime("2000-01-01t00:00:00-05:30"));
    print(parse_datetime("0001-01-01T00:00:00Z"), " ", parse_datetime("9999-12-31T23:59:59Z"), " ", parse_datetime("2026-11-27T10:30:00z"));
    print(parse_datetime("2026-11-27"), " ", parse_datetime("2026-11-27T24:00"), " ", parse_datetime("2026-11-27T10:60"), " ", parse_datetime("2026-11-27T10:30:60"));
    print(parse_datetime("2026-02-30T10:30"), " ", parse_datetime("2026-11-27T10:30:00."), " ", parse_datetime("2026-11-27T10:30+01"), " ", parse_datetime("2026-11-27T10:30:00ZZ"));
    print(parse_datetime("2026-11-27X10:30"), " ", parse_datetime("2026-11-27T1:30"), " ", parse_datetime("2026-11-27T10:30:00+01:00 "), " ", parse_datetime(""));
    print(format_datetime(0), " ", format_datetime(-1), " ", format_datetime(1795769400), " ", format_datetime(-62135596800), " ", format_datetime(253402300799));
    match parse_datetime("2026-11-27T10:30:00+01:00") {
        Option.Some(t) => { print(format_datetime(t)); }
        Option.None => { }
    }
}
