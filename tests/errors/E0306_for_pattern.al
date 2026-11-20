// expect: E0306
// The pattern of a for loop takes every element apart, so it must match them all.
fn main() {
    let xs: list[Option<i64>; 4] = [Option.Some(1), Option.None];
    for Option.Some(v) in xs {
        print(v);
    }
}
