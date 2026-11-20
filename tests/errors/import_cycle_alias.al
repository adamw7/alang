// expect: E0703
// A cycle back to the main file through `as` is a cycle, not a clash of scopes.
import "lib/cycle_alias.al" as other;

fn from_main() -> i64 {
    return 1;
}

fn main() {
    print(other.from_other());
}
