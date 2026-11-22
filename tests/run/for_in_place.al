// `for x in mut xs`: x is each element itself, and what the body does to x it does to the element.
struct Item {
    name: str[8],
    qty: i64,
}

fn restock(items: mut list[Item; 8], by: i64) {
    for it in mut items {
        it.qty += by;
    }
}

fn shout(s: mut str[_]) {
    for c in mut s {
        c = to_upper(c);
    }
}

fn main() {
    var xs: list[i64; 8] = [1, 2, 3];
    for x in mut xs {
        x *= 10;
    }
    print(xs);
    var grid: [[i64; 3]; 2] = [[1, 2, 3], [4, 5, 6]];
    for i, row in mut grid {
        for v in mut row {
            v += i;
        }
        push_front_marker(mut row);
    }
    print(grid);
    var items: list[Item; 8] = [Item(name: "pen", qty: 1), Item(name: "cup", qty: 0)];
    restock(mut items, 5);
    print(items);
    var prices: map[str[8], i64; 8] = [];
    prices["tea"] = 3;
    prices["jam"] = 4;
    for k, v in mut prices {
        if k == "tea" {
            v = 30;
        } else {
            v *= 2;
        }
    }
    print(prices);
    var name: str[16] = "ann lee";
    shout(mut name);
    print(name);
    var lists: list[list[i64; 4]; 4] = [[1], [2, 3]];
    for l in rev mut lists {
        push(mut l, len(l));
    }
    print(lists);
}

fn push_front_marker(row: mut [i64; 3]) {
    row[0] = -row[0];
}
