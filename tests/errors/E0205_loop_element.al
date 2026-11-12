// expect: E0205
struct Item {
    count: i64,
}

const START: [i64; 2] = [1, 2];

fn main() {
    var items: list[Item; 4] = [Item(count: 1)];
    for item in items {
        item.count += 1;
    }
    var grid: list[list[i64; 4]; 4] = [[1]];
    for i in 0..2 {
        for row in grid {
            push(mut row, i);
        }
    }
    for s in START {
        s = 0;
    }
}
