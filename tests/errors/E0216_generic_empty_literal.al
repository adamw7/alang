// expect: E0216
// `[]` says nothing of what T is, whatever collection the parameter is: the call is reported, not run.
fn tally<T>(xs: list[T; 4]) -> i64 {
    return len(xs);
}

fn distinct<T>(xs: set[T; _]) -> i64 {
    return len(xs);
}

fn main() {
    print(tally([]));
    print(distinct([]));
}
