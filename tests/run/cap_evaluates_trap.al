// cap() takes its value from the type, but its argument is evaluated, as every argument is: an index out of bounds
// traps in the interpreter as it does in C, and so does a missing key.
fn main() {
    let rows: list[str[8]; 2] = ["ab"];
    print(cap(rows[0]), " ", cap(rows));
    print(cap(rows[1]));
}
