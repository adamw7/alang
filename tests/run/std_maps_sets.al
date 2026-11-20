// get, first_key, last_key, floor_key, ceil_key, is_subset and symmetric_difference.
struct Stock {
    qty: i64,
    tags: list[str[4]; 2],
}

fn main() {
    var m: map[str[8], Stock; 8] = [];
    m["pear"] = Stock(qty: 3, tags: ["g"]);
    m["fig"] = Stock(qty: 0, tags: []);
    m["kiwi"] = Stock(qty: 7, tags: ["b", "c"]);

    // get: an Option, a copy of the value; a key of a narrower str is found.
    let short: str[4] = "fig";
    print(get(m, "pear"), " ", get(m, "plum"), " ", get(m, short));
    if let Option.Some(s) = get(m, "kiwi") {
        var copy: Stock = s;
        copy.qty = 100;
        print(copy.qty, " ", m["kiwi"].qty);
    }

    // first_key, last_key, floor_key and ceil_key: keys in order, at, before or after a key.
    print(first_key(m), " ", last_key(m));
    for k in ["a", "fig", "g", "kiwi", "l", "pear", "z"] {
        print(k, ": ", floor_key(m, k), " ", ceil_key(m, k));
    }
    let empty: map[i64, i64; 4] = [];
    print(first_key(empty), " ", last_key(empty), " ", floor_key(empty, 1), " ", ceil_key(empty, 1));

    // On sets too.
    let s: set[i64; 8] = [10, 20, 30];
    print(first_key(s), " ", last_key(s), " ", floor_key(s, 25), " ", ceil_key(s, 25), " ", floor_key(s, 5), " ",
        ceil_key(s, 31), " ", ceil_key(s, 20));
    let fs: set[f64; 4] = [0.5, 1.5];
    print(floor_key(fs, 1.0), " ", ceil_key(fs, 1.0));

    // is_subset: of sets of any capacities; the empty set is in every set.
    let small: set[i64; 2] = [20, 30];
    let none: set[i64; 4] = [];
    print(is_subset(small, s), " ", is_subset(s, small), " ", is_subset(none, small), " ", is_subset(s, s), " ",
        is_subset([10, 15], s), " ", is_subset(s, none));
    let words: set[str[8]; 4] = ["a", "b"];
    let more: set[str[8]; 8] = ["a", "b", "c"];
    print(is_subset(words, more), " ", is_subset(more, words));

    // symmetric_difference: in exactly one of the two, as a set of the type of the first.
    print(symmetric_difference(s, small), " ", symmetric_difference(small, s), " ", symmetric_difference(s, none), " ",
        symmetric_difference(none, small), " ", symmetric_difference(words, more));
    let x: set[i64; 8] = symmetric_difference(s, [5, 10, 40]);
    print(x, " ", len(x));
}
