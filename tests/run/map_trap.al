// Reading a key the map does not have traps; get_or and contains avoid it.
fn main() {
    var stock: map[str[8], i64; 4] = [("apple", 3)];
    for k, v in stock {
        stock[k] = v + 1;
        stock["pear"] = 1;
    }
    print(stock);
    print(stock["kiwi"]);
}
