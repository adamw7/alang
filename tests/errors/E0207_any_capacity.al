// expect: E0207
// Only the `_` takes any value: the elements of a `mut` list keep their exact type.
fn add_one(xs: mut list[i64; _]) {
    push(mut xs, 1);
}

fn main() {
    var xs: list[i32; 4] = [];
    add_one(mut xs);
    print(xs);
}
