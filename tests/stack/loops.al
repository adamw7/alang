// A for loop goes through a collection its body leaves alone where it is, without copying it; split and
// words in a loop make one piece at a time, without a list; and a let/var that split, words, join or
// replace initializes is filled in place, not through a temporary. Each copy the C made beyond these
// would overrun the bound by 600 KB or more.
struct Big {
    key: i64,
    pad: [u8; 100000],
}

fn total(items: list[Big; 10]) -> i64 {
    var sum: i64 = 0;
    for b in items {
        sum += b.key;
    }
    return sum;
}

fn main() {
    var items: list[Big; 10] = [];
    for i in 0..10 {
        push(mut items, Big(key: i, pad: [u8(i); 100000]));
    }
    var keys: i64 = 0;
    for b in items {
        keys += b.key + i64(b.pad[0]);
    }
    print(keys, " ", total(items));

    var text: str[600000] = "";
    for i in 0..60000 {
        append(mut text, "w", i % 7, " ");
    }
    var words_seen: i64 = 0;
    var longest: i64 = 0;
    for w in words(text) {
        words_seen += 1;
        longest = max(longest, len(w));
    }
    var fields: i64 = 0;
    for f in split(text, ' ') {
        fields += 1;
    }
    let parts: list[str[4]; 60000] = words(text);
    let joined: str[600000] = join(parts, ",");
    print(words_seen, " ", longest, " ", fields, " ", len(parts), " ", len(joined), " ", by_separator());
}

// A separator in a variable is copied before the first piece; the bound counts the copy.
fn by_separator() -> i64 {
    var sep: str[300000] = ";";
    var n: i64 = 0;
    for p in split("a;b;c", sep) {
        n += len(p);
        clear(mut sep);
    }
    return n;
}

test "separator copy" {
    assert by_separator() == 3;
}
