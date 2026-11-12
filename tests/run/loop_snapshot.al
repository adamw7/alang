// A for loop goes through what its collection held before the first iteration, whatever the body does to
// it: a variable the body changes is copied first.
struct Bag {
    items: list[i64; 8],
    seen: i64,
}

fn drain(xs: mut list[i64; 8]) -> i64 {
    var sum: i64 = 0;
    for x in xs {
        sum += x;
        clear(mut xs);
    }
    return sum;
}

fn main() {
    var xs: list[i64; 8] = [1, 2, 3];
    for x in xs {
        push(mut xs, x * 10);
    }
    print(xs);

    var ys: list[i64; 8] = [1, 2, 3];
    for y in ys {
        ys[2] = 99;
        print("y = ", y);
    }

    var bag: Bag = Bag(items: [4, 5], seen: 0);
    for v in bag.items {
        bag.seen += v;
        bag.items[0] = 0;
    }
    print(bag);

    var zs: list[i64; 8] = [7, 8, 9];
    let drained: i64 = drain(mut zs);
    print(drained, " ", len(zs));

    // A variable the body leaves alone is gone through where it is.
    let fixed: list[i64; 8] = [1, 2];
    var other: list[i64; 8] = [];
    for f in fixed {
        push(mut other, f);
    }
    print(other);
}
