// Arrays (fixed length) and lists (fixed capacity) are values.
struct Pair {
    a: i32,
    b: [i32; 3],
}

fn sum(xs: list[i32; 16]) -> i64 {
    var t: i64 = 0;
    for x in xs {
        t += i64(x);
    }
    return t;
}

fn main() {
    var xs: list[i32; 16] = [3, 1, 4];
    push(mut xs, 1);
    push(mut xs, 5);
    print(xs, " len=", len(xs), " cap=", cap(xs), " sum=", sum(xs));
    let small: list[i32; 4] = [9, 9];
    print(sum(small), " ", small == [9, 9], " ", xs == small);
    xs[0] = 30;
    let popped: i32 = pop(mut xs);
    print(xs, " popped ", popped);
    clear(mut xs);
    print(xs, " ", len(xs));

    let grid: [[i32; 3]; 2] = [[1, 2, 3], [4, 5, 6]];
    var g2: [[i32; 3]; 2] = grid;
    g2[1][2] = 60;
    print(grid, " ", g2, " ", grid == g2);

    let p: Pair = Pair(b: [7, 8, 9], a: 1);
    var q: Pair = p;
    q.b[0] = 70;
    print(p, " ", q);

    let zeros: [u8; 5] = [0; 5];
    let filled: list[Pair; 4] = [Pair(a: 2, b: [0; 3]); 2];
    print(zeros, " ", filled);
    var ps: list[Pair; 4] = filled;
    ps[1].b[2] = 99;
    print(ps[1], " ", filled[1]);
    for row in grid {
        print(row, " ", len(row));
    }
}
