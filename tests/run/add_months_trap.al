fn main() {
    let d: i64 = days_from_date(9999, 12, 31);
    print(format_date(add_months(d, -1)));
    print(add_months(d, i64.MAX));
}
