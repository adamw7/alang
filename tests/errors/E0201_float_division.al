// expect: E0201
// An average of integers stored in an f64. The hints convert the operands before dividing: converting
// one side to the left operand's type, and then the quotient to f64, would divide integers and silently
// drop the fraction. A literal stays as it is, and an integer conversion already written is replaced.
fn main() {
    let xs: list[i32; 4] = [1, 2, 4];
    let total: i32 = 7;
    let a: f64 = total / len(xs);
    let b: f64 = total / i32(len(xs));
    let c: f64 = (total + 1) * len(xs);
    let d: f64 = i64(total) / 2;
    let e: i64 = total + len(xs);
    let f: i64 = 2.5 * len(xs);
}
