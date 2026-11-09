// append writes each part straight into the spare capacity; one that does not fit traps with its
// full length, and the parts before it stay appended.
struct Tag {
    name: str[8],
    weight: f64,
}

fn main() {
    var s: str[24] = "";
    append(mut s, "ab", 12);
    print(s);
    append(mut s, "-", Tag(name: "x\ny", weight: 1.5), "never");
}
