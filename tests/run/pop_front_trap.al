// pop_front of an empty list traps.
fn main() {
    var q: list[i64; 2] = [1];
    let a: i64 = pop_front(mut q);
    print(a);
    let b: i64 = pop_front(mut q);
    print(b);
}
