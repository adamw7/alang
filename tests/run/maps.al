// Maps and sets: fixed capacity, always in key order.
fn word_counts(text: str[_]) -> map[str[16], i64; 32] {
    var counts: map[str[16], i64; 32] = [];
    for w in words(text) {
        if len(w) > 16 {
            continue;
        }
        let word: str[16] = str[16](w);
        counts[word] = get_or(counts, word, 0) + 1;
    }
    return counts;
}

fn main() {
    let counts: map[str[16], i64; 32] = word_counts("the cat saw the dog and the cat");
    print(counts);
    print(len(counts), " ", cap(counts), " ", counts["the"], " ", contains(counts, "dog"), " ", contains(counts, "cow"));
    for word, n in counts {
        if n > 1 {
            print(word, " x", n);
        }
    }
    for w in rev counts {
        print(w);
    }
    var ages: map[str[8], i64; 4] = [("bob", 30), ("ann", 25), ("bob", 31)];
    ages["cy"] = 40;
    ages["ann"] += 1;
    insert(mut ages, "dee", 22);
    print(ages);
    let had_bob: bool = remove(mut ages, "bob");
    let had_zed: bool = remove(mut ages, "zed");
    print(had_bob, " ", had_zed, " ", ages);
    var seen: set[i64; 8] = [5, 1, 5, 3];
    insert(mut seen, 2);
    insert(mut seen, 3);
    print(seen, " ", len(seen), " ", contains(seen, 2), " ", contains(seen, 4));
    let removed: bool = remove(mut seen, 1);
    print(removed, " ", seen);
    let a: set[i64; 4] = [1, 2];
    let b: set[i64; 8] = [2, 1];
    print(a == b, " ", seen == b);
    var grid: map[(i64, i64), str[4]; 8] = [((0, 1), "a"), ((-1, 5), "b")];
    grid[(0, 0)] = "o";
    print(grid);
    var lists: list[set[str[4]; 2]; 3] = [["b", "a"], ["a"]];
    sort(mut lists);
    print(lists);
    clear(mut ages);
    print(ages, " ", len(ages));
}

test "a missing key traps, get_or does not" {
    let m: map[i64, i64; 4] = [(1, 10)];
    assert get_or(m, 2, -1) == -1;
    assert m[1] == 10;
}
