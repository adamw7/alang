// extend, fill, rotate, is_sorted, product, min_index, max_index, lower_bound and upper_bound.
struct Item {
    rank: i64,
    name: str[8],
}

fn main() {
    // extend: from a list or an array, widening each str; an empty one adds nothing.
    var words: list[str[8]; 6] = ["a"];
    let more: list[str[4]; 3] = ["bc", "d"];
    extend(mut words, more);
    extend(mut words, ["efgh"]);
    let none: list[str[8]; 2] = [];
    extend(mut words, none);
    print(words, " ", len(words));
    var full: list[i64; 3] = [1, 2];
    extend(mut full, [3]);
    print(full);

    // fill: every element, a copy each; a str's bytes; a list keeps its length.
    var items: list[Item; 4] = [Item(rank: 1, name: "a"), Item(rank: 2, name: "b")];
    fill(mut items, Item(rank: 0, name: "z"));
    items[0].rank = 9;
    print(items);
    var grid: [[i64; 2]; 2] = [[1, 2], [3, 4]];
    fill(mut grid, [7, 7]);
    print(grid);
    var stars: str[8] = "abc";
    fill(mut stars, '*');
    print(stars);

    // rotate: toward the front by k, wrapping; negative k toward the back; any integer type.
    var xs: list[i64; 8] = [1, 2, 3, 4, 5];
    rotate(mut xs, 2);
    print(xs);
    rotate(mut xs, -2);
    print(xs);
    rotate(mut xs, 12);
    print(xs);
    rotate(mut xs, u8(255));
    print(xs);
    rotate(mut xs, -9223372036854775807 - 1);
    print(xs);
    var arr: [str[4]; 3] = ["x", "y", "z"];
    rotate(mut arr, 1);
    print(arr);
    var text: str[8] = "hello";
    rotate(mut text, -1);
    print(text);
    var empty: list[i64; 4] = [];
    rotate(mut empty, 3);
    print(empty);

    // is_sorted: in the order sort gives.
    let nan: f64 = 0.0 / 0.0;
    print(is_sorted(xs), " ", is_sorted(empty), " ", is_sorted([1, 1, 2]), " ", is_sorted("abz"), " ", is_sorted("ba"));
    print(is_sorted([Item(rank: 1, name: "b"), Item(rank: 1, name: "c"), Item(rank: 2, name: "a")]), " ",
        is_sorted([1.0, nan]), " ", is_sorted([nan, 1.0]), " ", is_sorted(["ab", "a"]));

    // product: 1 when empty; floats; a narrow type.
    let bytes: list[u8; 4] = [2, 3, 4];
    print(product(xs), " ", product(empty), " ", product([0.5, 4.0]), " ", product(bytes));

    // min_index and max_index: the first of equal ones; a NaN only when it is first.
    let ys: [i64; 6] = [4, 1, 9, 1, 9, 0];
    print(min_index(ys), " ", max_index(ys), " ", min_index([2, 2]), " ", max_index([5]));
    print(min_index([nan, 1.0, 0.5]), " ", min_index([1.0, nan, 0.5]), " ", max_index([1.0, nan, 3.0]));

    // lower_bound and upper_bound on sorted lists and arrays.
    let sorted: [i64; 7] = [1, 2, 2, 2, 5, 7, 7];
    for x in [0, 1, 2, 3, 7, 8] {
        print(x, ": ", lower_bound(sorted, x), " ", upper_bound(sorted, x), " ", upper_bound(sorted, x) - lower_bound(sorted, x));
    }
    let names: list[str[8]; 4] = ["ann", "bob", "cy"];
    let wide: str[16] = "bobby";
    print(lower_bound(names, "b"), " ", upper_bound(names, "bob"), " ", lower_bound(names, wide), " ", lower_bound(empty, 1));
    let ranked: list[Item; 4] = [Item(rank: 1, name: "a"), Item(rank: 3, name: "c")];
    print(lower_bound(ranked, Item(rank: 2, name: "")), " ", upper_bound(ranked, Item(rank: 3, name: "c")));
}
