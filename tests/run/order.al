// Evaluation order is fixed: left to right, arguments before the call,
// struct fields in source order, the right-hand side before the assignment target.
struct P {
    a: i32,
    b: i32,
}

fn trace(label: str[8], v: i32) -> i32 {
    print("eval ", label);
    return v;
}

fn yes(label: str[8]) -> bool {
    print("cond ", label);
    return true;
}

fn no(label: str[8]) -> bool {
    print("cond ", label);
    return false;
}

fn main() {
    let s: i32 = trace("a", 1) + trace("b", 2) * trace("c", 3);
    print("s = ", s);
    let p: P = P(b: trace("b", 2), a: trace("a", 1));
    print(p);
    var xs: [i32; 2] = [0, 0];
    xs[trace("index", 1)] = trace("value", 5);
    print(xs);
    if no("1") and yes("2") {
        print("unreachable");
    }
    if yes("3") or no("4") {
        print("short-circuit ok");
    }
    print(trace("x", 1), trace("y", 2));
}
