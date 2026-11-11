// An empty string literal has type str[0], which the C backend stores with a one-byte buffer (C has no
// zero-length arrays), so each one takes 8 bytes, not 4: the bound must count the bytes C really uses.
fn count(n: i64) -> i64 {
    var total: i64 = 0;
    for s in [""; 400000] {
        total += len(s) + n;
    }
    return total;
}

fn main() {
    print(len([""; 400000]));
    print(count(1));
}
