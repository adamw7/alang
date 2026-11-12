// expect: W0306
import "lib/names.al";

fn helper() -> i64 {
    return 1;
}

fn _scratch() -> i64 {
    return 2;
}

fn used() -> i64 {
    return 3;
}

fn main() {
    print(used());
}
