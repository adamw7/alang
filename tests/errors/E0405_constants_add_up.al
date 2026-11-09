// expect: E0405
// max-stack: 1000
// A keeps 400 bytes and needs 400 more while its initializer builds the array: 800 fits. B would
// need 400 + 800 = 1200, and so would C.
const A: [i64; 50] = [1; 50];
const B: [i64; 50] = [2; 50];
const C: [i64; 50] = [3; 50];

fn main() { print(A[0] + B[0] + C[0]); }
