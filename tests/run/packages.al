// Packages declared in alang.json and imported by name; geo vendors its own, identical, copy of text,
// which is the same package, read once.
import "@text/fmt.al" as text;
import "@geo/geo.al" as geo;

fn main() {
    print(text.banner("hi"));
    print(geo.describe(geo.Point(x: 3, y: 4)));
    print(text.WIDTH);
}

test "a package's declarations, by the import's name" {
    assert geo.describe(geo.Point(x: 1, y: 2)) == "== 1,2..... ==";
}
