// expect: E0104
import "lib/names.al";

const LIMIT_ITEMS: i64 = 8;

fn total(x: i64) -> i64 {
    return x;
}

fn main() {
    print(LIMIT_ITEMS);
}
