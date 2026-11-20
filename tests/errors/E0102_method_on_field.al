// expect: E0102 E0106
// A method called on a field of a variable, p.xs.len(), reads like a function of an import (ns.Enum.Variant)
// but is reported, and fixed, as the method call it is; a field that does not exist is reported as such.
struct Box {
    xs: list[i64; 4],
    name: str[8],
}

const ORIGIN: Box = Box(xs: [], name: "o");

fn main() {
    var b: Box = Box(xs: [1, 2], name: "ab");
    b.xs.push(3);
    let n: i64 = b.xs.len() + 1;
    let k: i64 = ORIGIN.name.length();
    let z: i64 = b.nope.len();
    print(n, k, z, b);
}
