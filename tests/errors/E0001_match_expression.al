// expect: E0001
fn main() {
    let n: i64 = 5;
    let word: str[8] = match n { 1 => { "one" } _ => { "many" } };
}
