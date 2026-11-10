// expect: E0402
// max-stack: 1000
// Each statement builds a 504-byte array, within the budget on its own. The C backend gives each
// temporary its own variable and clang -O0 keeps all of them on the stack at once, so they add up.
fn main() {
    print([1; 63][0]);
    print([2; 63][0]);
}
