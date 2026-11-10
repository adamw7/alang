// An array literal compared with a list takes the list's type on either side of == and !=.
fn main() {
    let xs: list[i64; 4] = [1, 2];
    let empty: list[i64; 4] = [];
    print(xs == [1, 2], " ", [1, 2] == xs, " ", [1, 2, 3] != xs);
    print(empty == [], " ", [] == empty, " ", [0; 2] == xs, " ", [1, 2] == [1, 2]);
}
