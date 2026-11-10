// Pushed and repeated values, a match subject and the collection of a for loop over a call result
// are used where they are, not copied once more into memory the bound does not count.
struct S {
    xs: [i64; 15000],
}

enum Big {
    A([i64; 60000]),
    B,
}

fn make(i: i64) -> Big {
    if i > 0 {
        return Big.A([i; 60000]);
    }
    return Big.B;
}

fn mk(k: i64) -> list[S; 3] {
    var out: list[S; 3] = [];
    push(mut out, S(xs: [k; 15000]));
    return out;
}

fn main() {
    var l: list[S; 3] = [];
    push(mut l, S(xs: [1; 15000]));
    let arr: [S; 2] = [S(xs: [2; 15000]); 2];
    for s in mk(4) {
        print(s.xs[3]);
    }
    print(len(l), " ", arr[1].xs[0]);
    let b: Big = make(3);
    match b {
        Big.A(xs) => { print(xs[5]); }
        Big.B => { print("b"); }
    }
}
