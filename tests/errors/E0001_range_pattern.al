// expect: E0001
fn grade(score: i64) -> u8 {
    match score {
        90..101 => { return 'A'; }
        _ => { return 'F'; }
    }
}
