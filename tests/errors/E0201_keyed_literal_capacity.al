// expect: E0201
// Different constant keys that are more than the capacity: a key given twice counts once.
fn main() {
    let s: set[i64; 2] = [3, 1, 2, 3];
    let m: map[str[1], i64; 1] = [("a", 1), ("b", 2), ("a", 3)];
    print(s, m);
}
