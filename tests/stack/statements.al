// Three statements, each building an 800 KB array. In C they are three variables of one function,
// all on the stack at once, so the bound adds them up instead of taking the largest.
fn pick(i: i64) -> i64 {
    return i % 7;
}

fn main() {
    let i: i64 = pick(3);
    print([1; 100000][i]);
    print([2; 100000][i]);
    print([3; 100000][i]);
}
