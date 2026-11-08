// `mut` parameters: the only references. They point into the caller's frame.
struct Counter {
    hits: list[i64; 8],
    total: i64,
}

fn bump(n: mut i64) {
    n += 1;
}

fn record(c: mut Counter, v: i64) {
    push(mut c.hits, v);
    c.total += v;
    bump(mut c.total);
}

fn reset_last(xs: mut list[i64; 8]) {
    xs[len(xs) - 1] = 0;
}

fn main() {
    var c: Counter = Counter(hits: [], total: 0);
    record(mut c, 10);
    record(mut c, 20);
    print(c);
    bump(mut c.hits[0]);
    reset_last(mut c.hits);
    print(c);
    var arr: [i64; 3] = [1, 2, 3];
    bump(mut arr[2]);
    print(arr);
    let copy: Counter = c;
    record(mut c, 5);
    print(copy.total, " ", c.total);
}
