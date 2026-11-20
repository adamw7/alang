// An integer product that does not fit its type traps, as * does.
fn main() {
    let small: list[u8; 4] = [5, 10, 5];
    print(product(small));
    let big: list[u8; 4] = [5, 10, 6];
    print(product(big));
}
