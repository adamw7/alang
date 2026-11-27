// next_permutation, next_combination, windows, chunks, unique, frequencies, prefix_sums, first and last.
struct Pair {
    key: str[4],
    n: i64,
}

fn main() {
    var p: [i64; 3] = [1, 2, 3];
    var orders: i64 = 0;
    var go: bool = true;
    while go limit 10 {
        orders += 1;
        print(p);
        go = next_permutation(mut p);
    }
    print(orders, " ", p);
    var dup: list[i64; 4] = [1, 1, 2];
    var seen: list[str[16]; 8] = [];
    var more: bool = true;
    while more limit 10 {
        var s: str[16] = "";
        append(mut s, dup);
        push(mut seen, s);
        more = next_permutation(mut dup);
    }
    print(seen);
    var word: str[4] = "bca";
    let w1: bool = next_permutation(mut word);
    var one: list[f64; 2] = [1.5];
    let w2: bool = next_permutation(mut one);
    var none: list[str[2]; 2] = [];
    let w3: bool = next_permutation(mut none);
    print(word, " ", w1, " ", w2, " ", w3);
    var idx: [i64; 2] = [0, 1];
    var combos: i64 = 0;
    var next: bool = true;
    while next limit 10 {
        combos += 1;
        print(idx);
        next = next_combination(mut idx, 4);
    }
    print(combos, " ", idx);
    var empty: list[i64; 2] = [];
    let e1: bool = next_combination(mut empty, 3);
    var all: list[i64; 3] = [0, 1, 2];
    let e2: bool = next_combination(mut all, 3);
    print(e1, " ", e2, " ", all);
    let xs: list[i64; 8] = [1, 2, 3, 4, 5];
    for w in windows(xs, 2) {
        print(w, " ", len(w), " ", cap(w));
    }
    for i, w in windows([10, 20, 30], 3) {
        print(i, ": ", w);
    }
    for w in windows(xs, 6) {
        print("never ", w);
    }
    for c in chunks(xs, 2) {
        print(c);
    }
    for i, c in chunks("abcdefg", 3) {
        print(i, " ", c, " ", len(c));
    }
    for c in chunks(xs, u8(200)) {
        print("all ", c);
    }
    var drops: i64 = 0;
    for w in windows([5, 3, 4, 1], 2) {
        if w[1] < w[0] {
            drops += 1;
        }
    }
    print("drops ", drops);
    var u: list[i64; 8] = [3, 1, 3, 2, 1, 3];
    unique(mut u);
    var us: str[16] = "mississippi";
    unique(mut us);
    var ps: list[Pair; 4] = [Pair(key: "a", n: 1), Pair(key: "b", n: 2), Pair(key: "a", n: 1)];
    unique(mut ps);
    var fs: list[f64; 4] = [0.5, 0.5, -0.0, 0.0];
    unique(mut fs);
    print(u, " ", us, " ", len(ps), " ", fs);
    let words: list[str[8]; 8] = ["to", "be", "or", "not", "to", "be"];
    let c: map[str[8], i64; 8] = frequencies(words);
    let cb: map[u8, i64; 8] = frequencies("hello");
    let cn: map[i64, i64; 4] = frequencies([3, 1, 3, 3]);
    print(c, " ", cb, " ", cn);
    let wide: map[str[16], i64; 8] = frequencies(words);
    print(len(wide), " ", get_or(wide, "to", 0));
    print(prefix_sums([1, 2, 3, 4]), " ", prefix_sums(xs), " ", prefix_sums([0.5, 0.25]), " ", prefix_sums([u8(1), 2]));
    let nothing: list[i64; 3] = [];
    print(first(xs), " ", last(xs), " ", first(nothing), " ", last(nothing), " ", first("hi"), " ", last(""), " ", last(words));
}
