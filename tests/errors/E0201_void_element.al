// expect: E0201
// A call that returns nothing has no value, so it cannot be an array element.
fn nothing() { }
fn main() {
    print([nothing()], len([nothing(); 2]));
}
