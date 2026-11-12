// fixed() takes 0 to 20 decimals (R0013); the trap comes before anything of the print is written.
fn main() {
    var d: u64 = 20;
    print(fixed(1.0, d));
    d += 1;
    print("never: ", fixed(1.0, d));
}
