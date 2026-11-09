// expect: E0001
fn main() {
    while true limit 3 {
        break print(1);
    }
}
