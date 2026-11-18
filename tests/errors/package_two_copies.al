// expect: E0706
import "@text/fmt.al" as text;
import "@twin/twin.al" as twin;

fn main() {
    print(text.shout("a"), twin.twin());
}
