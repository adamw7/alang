// expect: E0405
// max-stack: 8388608
// The checker computes capacities and repeat counts itself, as it computes constants, so what they build
// must fit the budget too: these 8 TiB used to exhaust its memory.
fn main() {
    let s: str[len([[0; 1048576]; 1048576])] = "";
    let xs: [i64; 2] = [0; len([[0; 1048576]; 1048576])];
    print(s, xs);
}
