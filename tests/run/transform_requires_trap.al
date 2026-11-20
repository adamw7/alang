// A function passed to transform or fold that breaks its contract traps at the call, after the elements before it.
fn halve(x: i64) -> i64
    requires x >= 0
{
    print("halve ", x);
    return x / 2;
}

fn main() {
    let xs: [i64; 3] = [4, -2, 6];
    print(transform(xs, halve));
}
