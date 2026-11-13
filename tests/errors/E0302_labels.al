// expect: E0105 E0202 E0212 E0302
// A labelled break or continue names a loop around it; a range has no index and counts up by a positive step.
fn main() {
    outer: for i in 0..3 {
        outer: for j in 0..3 {
            print(i, j);
        }
        break inner;
    }
    for k in 0..10 {
        continue outer;
    }
    for i, x in 0..3 {
        print(i, x);
    }
    for i in 10..0 step -1 {
        print(i);
    }
}
