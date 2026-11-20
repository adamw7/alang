// A `mut` list of any capacity is still full at its own capacity: push traps there (R0004).
fn fill_up(xs: mut list[i64; _], n: i64) {
    for i in 0..n {
        push(mut xs, i);
    }
}

fn main() {
    var big: list[i64; 8] = [];
    fill_up(mut big, 5);
    print(big);
    var small: list[i64; 3] = [];
    fill_up(mut small, 5);
    print(small);
}
