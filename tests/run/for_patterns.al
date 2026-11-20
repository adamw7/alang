// for P in xs: each element taken apart by a pattern that always matches.
struct Point {
    x: i64,
    y: i64,
}

fn main() {
    let pairs: list[(str[4], i64); 4] = [("a", 1), ("b", 2), ("c", 3)];
    for (name, n) in pairs {
        print(name, "=", n);
    }
    for i, (name, _) in pairs {
        print(i, ":", name);
    }
    for (_, n) in rev pairs {
        print(n);
    }
    let pts: [Point; 2] = [Point(x: 1, y: 2), Point(x: 3, y: 4)];
    for Point(x: px, y: py) in pts {
        print(px * py);
    }
    var m: map[str[4], (i64, i64); 4] = [];
    m["k"] = (1, 2);
    m["j"] = (3, 4);
    for k, (lo, hi) in m {
        print(k, " ", hi - lo);
    }
    for (a, (b, c)) in [(1, (2, 3)), (4, (5, 6))] {
        print(a + b + c);
    }
    // The loop goes through a copy, so changing the list in the body does not change what it sees.
    var grow: list[(i64, i64); 8] = [(1, 1), (2, 2)];
    for (x, y) in grow {
        push(mut grow, (x * 10, y * 10));
    }
    print(grow);
    var total: i64 = 0;
    for _ in 0..3 {
        total += 1;
    }
    print(total);
}
