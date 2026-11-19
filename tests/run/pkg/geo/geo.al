import "@text/fmt.al" as text;

struct Point {
    x: i64,
    y: i64,
}

fn describe(p: Point) -> str[64] {
    var s: str[16] = "";
    append(mut s, p.x, ",", p.y);
    return text.banner(s);
}
