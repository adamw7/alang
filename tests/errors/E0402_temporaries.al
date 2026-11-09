// expect: E0402
// max-stack: 1000
// No variable at all, but the statement builds an 8000-byte array: temporaries count.
fn main() { print([0; 1000][0]); }
