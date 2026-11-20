// split_once on an empty separator traps.
fn main() {
    let sep: str[4] = "";
    print(split_once("a=b", "="));
    print(split_once("a=b", sep));
}
