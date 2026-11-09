// expect: E0402
// max-stack: 1000
// `show` gets xs by address (8 bytes), but the loop iterates over a 504-byte copy of it. With main's
// 504-byte literal that is 1024 bytes; without counting the copy it would be 520.
fn show(xs: [i64; 63]) {
    for x in xs {
        print(x);
    }
}

fn main() { show([0; 63]); }
