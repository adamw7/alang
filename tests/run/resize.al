// str[N](s) and list[T; N](xs): the same text or elements with capacity N, checked.
struct Rec {
    tag: str[4],
}

const SHORT: str[3] = str[3]("abc");

fn tag_of(s: str[64]) -> Rec {
    return Rec(tag: str[4](s));
}

fn main() {
    let big: str[256] = "hello";
    let small: str[5] = str[5](big);
    let wide: str[300] = str[300](small);
    print(small, " ", len(small), " ", cap(small), " ", cap(wide), " ", small == big);
    print(SHORT, " ", cap(SHORT), " [", str[1](""), "]");
    print(tag_of("ab"));
    let xs: list[i64; 64] = [1, 2, 3];
    let ys: list[i64; 3] = list[i64; 3](xs);
    print(ys, " ", cap(ys), " ", ys == xs);
    let none: list[str[8]; 2] = list[str[8]; 2]([]);
    print(len(none), " ", cap(list[i64; 9](ys)));
    // The result is a value of its own: changing it leaves the original alone.
    var copy: str[8] = str[8](big);
    push(mut copy, '!');
    print(big, " ", copy);
    for c in str[2]("hi") {
        print(c);
    }
}
