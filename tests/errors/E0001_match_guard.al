// expect: E0001
fn sign(x: i64) -> i64 {
    match x {
        _ if x < 0 => { return -1; }
        _ => { return 1; }
    }
}
