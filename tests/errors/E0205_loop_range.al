// expect: E0205
fn main() {
    let text: str[8] = "a,,b";
    for i in 0..len(text) {
        if text[i] == ',' {
            i += 1;
        }
    }
    for j in 2..len(text) {
        j = j + 2;
    }
}
