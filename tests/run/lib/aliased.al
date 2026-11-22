// Imported as geo by type_aliases.al: its aliases are geo.Path.
struct Point {
    x: i64,
    y: i64,
}

type Path = list[Point; 8];

fn origin() -> Point {
    return Point(x: 0, y: 0);
}

fn length(p: Path) -> i64 {
    var total: i64 = 0;
    for i in 1..len(p) {
        total += abs(p[i].x - p[i - 1].x) + abs(p[i].y - p[i - 1].y);
    }
    return total;
}
