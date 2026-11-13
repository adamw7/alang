// expect: E0201 E0203 E0207 E0212
// Maps and sets: keys of the map's key type, entries as (key, value) pairs, and a set has no positions.
fn main() {
    var m: map[str[8], i64; 4] = [("a", 1)];
    let bad: map[str[8], i64; 4] = [1, 2];
    var s: set[i64; 4] = [3, 1];
    print(m[1], s[0]);
    push(mut m, 3);
    insert(m, "b", 2);
    let x: i64 = get_or(s, 1, 0);
    print(bad, x);
}
