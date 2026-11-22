// fn f<N>(xs: list[T; N]): a name for a capacity, which each call fixes like a `_`, and which the result's type,
// the types in the body and its code can use.
fn doubled<T, N>(xs: list[T; N]) -> list[T; N] {
    var out: list[T; N] = [];
    for x in xs {
        if len(out) < N {
            push(mut out, x);
        }
    }
    return out;
}

fn reversed<N>(a: [i64; N]) -> [i64; N] {
    var out: [i64; N] = a;
    for i in 0..N {
        out[i] = a[N - 1 - i];
    }
    return out;
}

fn same_size<N>(a: str[N], b: str[N]) -> i64 {
    return cap(a) + cap(b) + N;
}

fn concat<N>(a: list[i64; N], b: list[i64; N]) -> list[i64; N] {
    var out: list[i64; N] = a;
    for x in b {
        if len(out) < cap(out) {
            push(mut out, x);
        }
    }
    return out;
}

fn main() {
    let xs: list[str[4]; 3] = ["a", "b"];
    let d: list[str[4]; 3] = doubled(xs);
    print(d, " ", cap(d));
    print(reversed([1, 2, 3, 4]), " ", reversed([9]));
    let a: str[8] = "ab";
    let b: str[8] = "c";
    print(same_size(a, b));
    let l1: list[i64; 5] = [1, 2];
    let l2: list[i64; 5] = [3, 4, 5, 6];
    print(concat(l1, l2));
}
