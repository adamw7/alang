// min_index and max_index of an empty list trap, as min and max do.
fn main() {
    let xs: list[f64; 4] = [2.5, 0.5];
    print(min_index(xs), " ", max_index(xs));
    let none: list[f64; 4] = [];
    print(max_index(none));
}
