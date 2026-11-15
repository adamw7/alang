// A builtin's `mut` argument m[k] is evaluated first, as every first argument is: push to a missing key traps on
// the key before the value is computed, in C too, where the lookup used to run after the value.
fn main() {
    var lists: map[str[4], list[i64; 2]; 4] = [("a", [1])];
    var zero: i64 = 0;
    push(mut lists["a"], 2);
    print(lists);
    push(mut lists["b"], 1 / zero);
}
