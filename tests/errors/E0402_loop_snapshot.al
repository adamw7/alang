// expect: E0402
// max-stack: 1400
// The loop changes `xs`, so it goes through a 504-byte copy of it, taken before the first iteration.
// With xs itself and the literal that fills it (504 bytes each) that is 1512 bytes; without counting the
// copy it would be 1008. A loop that leaves its collection alone needs no copy (tests/stack/loops.al).
fn main() {
    var xs: [i64; 63] = [0; 63];
    for x in xs {
        xs[0] += x;
    }
    print(xs[0]);
}
