// The standard library's results are temporaries the bound counts, and its helpers copy no element to the
// stack: sort and reverse swap bytes in place, and split, join and replace write straight into the result.
struct Big {
    key: i64,
    pad: [u8; 100000],
}

fn main() {
    var items: list[Big; 9] = [];
    for i in 0..8 {
        push(mut items, Big(key: 8 - i, pad: [u8(i); 100000]));
    }
    sort(mut items);
    reverse(mut items);
    let last: Big = items[7];
    insert(mut items, 0, last);
    print(items[0].key, " ", items[8 - 1].key);
    let gone: Big = remove(mut items, 0);
    print(gone.key, " ", gone.pad[0], " ", index_of(items, gone), " ", len(slice(items, 2, 5)));

    var text: str[200000] = "";
    for i in 0..20000 {
        append(mut text, "w", i % 7, " ");
    }
    let parts: list[str[16]; 20000] = words(text);
    let joined: str[200000] = join(parts, ",");
    let swapped: str[300000] = replace(joined, ",", "; ");
    let loud: str[200000] = to_upper(trim(text));
    print(len(parts), " ", len(joined), " ", len(swapped), " ", contains(loud, "W6 W0"), " ", index_of(swapped, "w3; "));
}
