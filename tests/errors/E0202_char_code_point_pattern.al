// expect: E0202
fn main() {
    for c in "café" {
        match c {
            'é' => { print("e"); }
            _ => { }
        }
    }
}
