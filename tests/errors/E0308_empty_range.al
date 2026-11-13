// expect: E0308 E0202
fn bucket(n: u8) -> i64 {
    match n {
        10..=1 => { return 0; }
        0..=300 => { return 1; }
        _ => { return 2; }
    }
}

fn main() {
    print(bucket(5));
}
