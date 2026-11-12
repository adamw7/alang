// expect: E0205
struct Item {
    name: str[8],
    tags: list[str[8]; 4],
    qty: i64,
}

fn main() {
    var items: list[Item; 4] = [Item(name: "a", tags: [], qty: 1)];
    let it: Item = items[0];
    it.qty += 1;
    push(mut it.tags, "sale");
    let n: i64 = 1;
    n = 2;
    print(items, " ", it, " ", n);
}
