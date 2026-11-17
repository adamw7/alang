// expect: E0219
struct Point { x: i64, y: i64 }

fn main() {
    let xs: list[str[32]; 4] = ["a ", "b"];
    let x = 5;
    let name = trim(xs[0]);
    var p = Point(x: 1, y: 2);
    var s = "hi";
    let w = to_upper("abc");
    let e = [];
    let o = Option.None;
    print(x, name, p, s, w, e, o);
}
