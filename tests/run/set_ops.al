// union, intersection and difference of sets, in order, as the first set's type.
fn main() {
    let a: set[i64; 8] = [1, 3, 5, 7];
    let b: set[i64; 4] = [3, 4, 5];
    let none: set[i64; 4] = [];
    print(union(a, b), " ", intersection(a, b), " ", difference(a, b), " ", difference(b, a));
    print(union(none, b), " ", intersection(a, none), " ", difference(a, none), " ", union(none, none));
    let u: set[i64; 8] = union(a, b);
    print(len(u), " ", cap(u), " ", contains(u, 4));
    let names: set[str[8]; 4] = ["ann", "bob"];
    let more: set[str[8]; 8] = ["bob", "cy", "al"];
    var all: set[str[8]; 4] = union(names, intersection(more, more));
    remove(mut all, "ann");
    print(all, " ", names, " ", difference(more, names));
    let words: set[str[4]; 4] = ["x"];
    print(union(words, ["a", "z"]));
}
