// What get_or returns, and a constant of a file imported under a name, are copies: changing the copy changes
// neither the map, nor the default it was given, nor the constant. insert copies into a set or map, as push does.
import "lib/tables.al" as tables;

const ONE: list[i64; 4] = [1];

struct Box {
    name: str[8],
    xs: list[i64; 4],
}

fn main() {
    let empty: map[i64, list[i64; 4]; 2] = [];
    var a: list[i64; 4] = get_or(empty, 1, ONE);
    push(mut a, 2);
    print(ONE, " ", a);

    let d: list[i64; 4] = [4];
    var b: list[i64; 4] = get_or(empty, 1, d);
    push(mut b, 5);
    print(d, " ", b);

    let m: map[i64, list[i64; 4]; 2] = [(1, [7])];
    var c: list[i64; 4] = get_or(m, 1, d);
    push(mut c, 8);
    print(m, " ", c);

    var p: list[i64; 4] = tables.PRIMES;
    push(mut p, 7);
    print(tables.PRIMES, " ", p);
    var q: list[i64; 4] = if len(p) > 0 then tables.PRIMES else [];
    push(mut q, 11);
    print(tables.PRIMES, " ", q);

    var names: set[str[8]; 4] = [];
    var name: str[8] = "bo";
    insert(mut names, name);
    insert(mut names, "al");
    insert(mut names, name);
    push(mut name, 'b');
    print(names, " ", name);

    var boxes: map[str[8], Box; 2] = [];
    var bx: Box = Box(name: "x", xs: [1]);
    insert(mut boxes, "first", bx);
    push(mut bx.xs, 2);
    insert(mut boxes, bx.name, bx);
    print(boxes);

    var opts: set[Option<str[4]>; 3] = [];
    insert(mut opts, Option.Some("a"));
    insert(mut opts, Option.None);
    print(opts);
}
