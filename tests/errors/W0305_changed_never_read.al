// expect: W0305
struct Item {
    name: str[8],
    qty: i64,
}

fn main() {
    var items: list[Item; 4] = [Item(name: "a", qty: 1)];
    var it: Item = items[0];
    it.qty += 1;
    var log: str[32] = "";
    append(mut log, "restocked ", items[0].name);
    var n: i64 = 0;
    for i in items {
        n += i.qty;
    }
    print(items);
}
