// expect: E0214
// Text converts to integers and to f64; an f32 comes from the f64.
fn main() {
    let x: f32 = f32("4.2");
    print(x);
}
