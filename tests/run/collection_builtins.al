// sum, min and max over a collection, last_index_of, dedup and binary_search; f64 with an exponent.
struct Item {
    rank: i64,
    name: str[8],
}

fn main() {
    let xs: list[i64; 8] = [3, 1, 4, 1, 5, 9, 2, 6];
    let fs: [f32; 3] = [0.5, 0.25, 0.125];
    let bytes: list[u8; 4] = [];
    print(sum(xs), " ", min(xs), " ", max(xs), " ", sum(xs[2..5]), " ", sum(bytes));
    print(sum(fs), " ", min(fs), " ", max(fs), " ", sum([0.1, 0.2, 0.3]));
    print(last_index_of(xs, 1), " ", last_index_of(xs, 7), " ", last_index_of(fs, 0.5));
    print(last_index_of("abcabc", "bc"), " ", last_index_of("abcabc", 'a'), " ", last_index_of("abc", ""), " ",
        last_index_of("abc", "abcd"), " ", last_index_of("", ""));

    var sorted: list[i64; 8] = xs;
    sort(mut sorted);
    dedup(mut sorted);
    print(sorted);
    var s: str[16] = "aabbbcdda";
    dedup(mut s);
    print(s);
    var items: list[Item; 4] = [Item(rank: 1, name: "a"), Item(rank: 1, name: "a"), Item(rank: 1, name: "b")];
    dedup(mut items);
    print(len(items), " ", binary_search(items, Item(rank: 1, name: "b")), " ", binary_search(items, Item(rank: 0, name: "z")));

    let dups: [i64; 7] = [1, 2, 2, 2, 2, 3, 5];
    for x in [0, 1, 2, 3, 4, 5, 6] {
        print(x, ": ", binary_search(dups, x), " ", binary_search(sorted, x));
    }
    let names: [str[8]; 3] = ["ann", "bob", "cy"];
    print(binary_search(names, "bob"), " ", binary_search(names, "b"), " ", binary_search(names, "zed"));

    let texts: [str[16]; 8] = ["1e5", "2.5E-3", "-6.02e+23", "0e999", "1e", "e5", "1e-30", "1.5e2.0"];
    for t in texts {
        if is_float(t) {
            print(t, " = ", f64(t));
        } else {
            print(t, " is not");
        }
    }
}
