// A step that is not positive traps when the loop starts.
fn total(step: i64) -> i64 {
    var n: i64 = 0;
    for i in 0..10 step step {
        n += i;
    }
    return n;
}

fn main() {
    print(total(3));
    print(total(0));
}
