// expect: E0203
fn is_even(x: i64) -> bool {
    return x % 2 == 0;
}

fn main() {
    var s: str[8] = "ab";
    var xs: list[i64; 8] = [1, 2];
    let set1: set[i64; 4] = [1, 2];
    var m: map[str[4], i64; 4] = [];
    m["a"] = 1;
    extend(mut s, "cd");
    extend(mut xs, 3);
    print(get(set1, 1));
    print(floor_key(xs, 1));
    print(first_key(xs));
    print(is_sorted(set1));
    print(lower_bound(m, "a"));
    print(transform("abc", is_even));
    print(zip(xs, set1));
    print(is_subset(xs, set1));
    print(fold("abc", 0, is_even));
}
