// expect: E0102
// Method calls: the hint keeps `mut` for the calls that modify their first argument, and a method call
// used as a statement is not also reported as having no effect.
fn grow(xs: mut list[i64; 4], by: i64) {
    push(mut xs, by);
}

fn main() {
    var xs: list[i64; 4] = [];
    var s: str[8] = "";
    xs.push(1);
    xs.grow(2);
    let top: i64 = xs.pop();
    xs.clear();
    s.append("x");
    print(xs.len(), top);
}
