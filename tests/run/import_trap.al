// A trap in an imported file is reported there; the trace names the call in this file.
import "lib/units.al";

fn main() {
    let none: list[i64; 4] = [];
    print(first(none));
}
