// count(xs, x): non-overlapping occurrences in a str, left to right; elements equal to x in a list or array.
struct P {
    x: i64,
    y: i64,
}

enum Color {
    Red,
    Green,
}

const COMMAS: i64 = count("a,b,,c", ',');

fn main() {
    print(count("a,b,,c", ','), " ", COMMAS, " ", count("a,b,,c", ",,"));
    print(count("aaaa", "aa"), " ", count("aaa", "aa"), " ", count("abc", "x"), " ", count("", "a"));
    let line: str[32] = "x=1; y=2; z=3";
    print(count(line, "; ") + 1, " fields");
    let xs: list[i64; 8] = [3, 1, 3, 3, 2];
    print(count(xs, 3), " ", count(xs, 7), " ", count([1, 1, 2], 1));
    let empty: list[i64; 4] = [];
    print(count(empty, 1));
    let ps: [P; 3] = [P(x: 1, y: 2), P(x: 0, y: 0), P(x: 1, y: 2)];
    print(count(ps, P(x: 1, y: 2)));
    let cs: list[Color; 4] = [Color.Red, Color.Green, Color.Red];
    print(count(cs, Color.Red));
    let names: list[str[8]; 4] = ["ann", "bob", "ann"];
    print(count(names, "ann"));
    let bytes: list[u8; 4] = [1, 2, 2];
    print(count(bytes, 2));
}
