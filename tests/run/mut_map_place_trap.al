// A `mut` argument m[k] is the value k has, so k must be in the map, as when m[k] is read: the call traps in both
// backends, even when the function only assigns to it and would have added the key in the interpreter. The key is
// looked up in its place among the arguments, before those after it are evaluated.
fn set_to(v: mut i64, x: i64) {
    v = x;
}

fn main() {
    var m: map[str[4], i64; 4] = [("a", 1)];
    set_to(mut m["a"], 2);
    var lists: map[str[4], list[i64; 2]; 4] = [("a", [])];
    push(mut lists["a"], m["a"]);
    print(m, " ", lists, " ", cap(lists["a"]));
    var zero: i64 = 0;
    set_to(mut m["b"], 1 / zero);
}
