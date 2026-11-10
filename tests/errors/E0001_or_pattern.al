// expect: E0001
fn is_weekend(day: i64) -> bool {
    match day {
        6 | 7 => { return true; }
        _ => { return false; }
    }
}
