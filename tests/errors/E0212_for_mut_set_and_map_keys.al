// expect: E0212
fn main() {
    var s: set[i64; 8] = [];
    insert(mut s, 1);
    for x in mut s {
        x += 1;
    }
    var m: map[i64, i64; 4] = [];
    m[1] = 2;
    for k in mut m {
        print(k);
    }
    print(s, m);
}
