// Days since 0000-03-01 (Howard Hinnant's days_from_civil, for years >= 1).
fn day_number(year: i64, month: i64, day: i64) -> i64 {
    var y: i64 = year;
    var mp: i64 = month - 3;
    if month <= 2 {
        y -= 1;
        mp = month + 9;
    }
    let era: i64 = y / 400;
    let yoe: i64 = y - era * 400;
    let doy: i64 = (153 * mp + 2) / 5 + day - 1;
    let doe: i64 = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe;
}

tool fn days_between(y1: i64, m1: i64, d1: i64, y2: i64, m2: i64, d2: i64) -> i64 {
    return day_number(y2, m2, d2) - day_number(y1, m1, d1);
}
