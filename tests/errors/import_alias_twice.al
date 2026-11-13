// expect: E0702
import "lib/names.al" as n;
import "lib/cycle_b.al" as n;

fn main() {
    print(n.LIMIT_ITEMS);
}
