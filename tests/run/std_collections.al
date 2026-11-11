// The standard library's collection functions: sort, reverse, insert, remove, contains, index_of.
struct Score {
    points: i64,
    name: str[8],
}

enum Shape {
    Dot,
    Circle(f64),
    Rect(f64, f64),
}

fn main() {
    var xs: list[i64; 16] = [5, -3, 9, 0, 5, 12, -40, 7];
    sort(mut xs);
    print(xs);
    reverse(mut xs);
    print(xs);
    insert(mut xs, 0, 100);
    insert(mut xs, len(xs), 200);
    insert(mut xs, 3, 300);
    print(xs);
    let first: i64 = remove(mut xs, 0);
    let last: i64 = remove(mut xs, len(xs) - 1);
    let mid: i64 = remove(mut xs, u8(2));
    print(first, " ", last, " ", mid, " ", xs);
    print(contains(xs, 9), " ", contains(xs, 10), " ", index_of(xs, 5), " ", index_of(xs, -40), " ", index_of(xs, 1));

    var arr: [u8; 6] = [3, 1, 2, 255, 0, 128];
    sort(mut arr);
    print(arr);
    reverse(mut arr);
    print(arr, " ", index_of(arr, 128), " ", contains(arr, 4));

    let nan: f64 = 0.0 / 0.0;
    var fs: list[f64; 8] = [2.5, nan, -0.0, 0.0, -1.0 / 0.0, 1.0 / 0.0, -0.0, -7.25];
    sort(mut fs);
    print(fs);
    print(index_of(fs, 0.0), " ", contains(fs, nan));

    var names: list[str[8]; 8] = ["pear", "apple", "fig", "", "apples", "Zoo", "app"];
    sort(mut names);
    print(names, " ", index_of(names, "fig"), " ", contains(names, "kiwi"));

    var bools: [bool; 4] = [true, false, true, false];
    sort(mut bools);
    print(bools);

    var scores: list[Score; 8] = [
        Score(points: 7, name: "cy"),
        Score(points: 9, name: "bo"),
        Score(points: 7, name: "al"),
        Score(points: -2, name: "dee"),
        Score(points: 7, name: "al"),
    ];
    sort(mut scores);
    print(scores);
    reverse(mut scores);
    print(scores[0], " ", index_of(scores, Score(points: 7, name: "al")));

    var shapes: list[Shape; 8] = [Shape.Rect(1.0, 2.0), Shape.Circle(3.0), Shape.Dot, Shape.Rect(1.0, 1.5), Shape.Circle(-1.0),
        Shape.Circle(3.0), Shape.Rect(1.0, 2.0)];
    sort(mut shapes);
    print(shapes, " ", contains(shapes, Shape.Dot));

    var grid: list[list[i64; 3]; 4] = [[2, 1], [1, 5, 0], [2], [1, 5]];
    sort(mut grid);
    print(grid);
    var rows: [[i32; 2]; 3] = [[3, 1], [1, 9], [1, 2]];
    sort(mut rows);
    print(rows);

    var word: str[16] = "banana";
    sort(mut word);
    print(word);
    reverse(mut word);
    print(word);
    insert(mut word, 0, 'X');
    insert(mut word, 3, '-');
    let c: u8 = remove(mut word, len(word) - 1);
    print(word, " ", c);
    let middle: u8 = remove(mut word, 2);
    print(word, " ", middle);
    var bytes: str[4] = "\xff\x01\x80a";
    sort(mut bytes);
    print(bytes[0], " ", bytes[1], " ", bytes[2], " ", bytes[3]);

    var empty: list[i64; 2] = [];
    sort(mut empty);
    reverse(mut empty);
    insert(mut empty, 0, 1);
    print(empty, " ", index_of(empty, 1));

    // A slice is a copy: of a list, or of an array as a list.
    let tail: list[i64; 16] = slice(xs, 1, len(xs));
    let firsts: list[u8; 6] = slice(arr, 0, 2);
    print(tail, " ", firsts);

    // Like pop, remove may be a statement of its own, dropping the element.
    remove(mut empty, 0);
    print(empty);
}

test "sorting is ascending and keeps every element" {
    var xs: list[i64; 8] = [4, 1, 3, 1, 2];
    sort(mut xs);
    assert xs == [1, 1, 2, 3, 4];
    for i in 1..len(xs) {
        assert xs[i - 1] <= xs[i];
    }
}
