// expect: E0204
// A for loop over split or words with the wrong number of arguments is reported, not a crash.
fn main() {
    for w in words() {
        print(w);
    }
    for p in split() {
        print(p);
    }
}
