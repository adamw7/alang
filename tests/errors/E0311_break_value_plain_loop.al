// expect: E0311
fn main() {
    while true limit 3 {
        break print(1);
    }
}
