// Functions whose parameters take any capacity: a `_` in place of a capacity or length.
struct Entry {
    key: str[16],
    value: i64,
}

fn total(xs: list[i64; _]) -> i64 {
    var sum: i64 = 0;
    for x in xs {
        sum += x;
    }
    return sum;
}

fn sum_array(xs: [i64; _]) -> i64
    requires len(xs) > 0
{
    // An instance of one function calls an instance of another, for the capacity it was made for.
    return total(slice(xs, 0, len(xs)));
}

fn lookup(entries: list[Entry; _], key: str[_]) -> i64 {
    for e in entries {
        if e.key == key {
            return e.value;
        }
    }
    return -1;
}

fn store(entries: mut list[Entry; _], key: str[_], value: i64) {
    for i in 0..len(entries) {
        if entries[i].key == key {
            entries[i].value = value;
            return;
        }
    }
    if len(key) > 16 {
        return;
    }
    push(mut entries, Entry(key: str[16](key), value: value));
}

fn shout(s: mut str[_]) {
    s = to_upper(trim(s));
    push(mut s, '!');
}

fn longest(words: list[str[_]; _]) -> i64 {
    var best: i64 = 0;
    for w in words {
        best = max(best, len(w));
    }
    return best;
}

fn fields(line: str[_]) -> i64 {
    var n: i64 = 0;
    for p in split(line, ',') {
        if len(trim(p)) > 0 {
            n += 1;
        }
    }
    return n;
}

fn sorted_copy_len(xs: mut list[i64; _]) -> i64 {
    sort(mut xs);
    reverse(mut xs);
    return cap(xs);
}

fn main() {
    var small: list[i64; 4] = [1, 2, 3];
    var big: list[i64; 100] = [10, 20];
    print(total(small), " ", total(big), " ", total([5, 6]));
    print(sum_array([4, 5, 6]), " ", sum_array([7]));

    var table: list[Entry; 8] = [];
    store(mut table, "apple", 3);
    let long: str[200] = "banana";
    store(mut table, long, 5);
    store(mut table, "apple", 4);
    store(mut table, "this key is much too long", 1);
    print(table);
    print(lookup(table, "banana"), " ", lookup(table, long), " ", lookup(table, "cherry"));

    var s: str[8] = " hey ";
    shout(mut s);
    var t: str[32] = "quiet";
    shout(mut t);
    print(s, " ", t, " ", cap(s), " ", cap(t));

    let ws: list[str[10]; 3] = ["a", "abcd", "ab"];
    print(longest(ws), " ", fields("a, b,,c"), " ", fields(long));

    let small_cap: i64 = sorted_copy_len(mut small);
    let big_cap: i64 = sorted_copy_len(mut big);
    print(small_cap, " ", small, " ", big_cap, " ", big);
}

test "every capacity" {
    let two: list[i64; 2] = [1, 2];
    let many: list[i64; 64] = [1, 2, 3, 4];
    assert total(two) == 3;
    assert total(many) == 10;
}
