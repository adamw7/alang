// A list widened to a parameter's type, and widened again on return.
fn total(xs: list[i64; 50000]) -> list[i64; 60000] {
    return xs;
}

fn main() {
    let l: list[i64; 40000] = [1, 2, 3];
    let r: list[i64; 60000] = total(l);
    print(len(r), " ", r[2]);
}
