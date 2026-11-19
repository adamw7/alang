// keys and values of a map, every variant of an enum, and the FNV-1a hash of bytes.
enum Color {
    Red,
    Green,
    Blue,
}

const ALL: [Color; 3] = variants(Color);
const EMPTY_HASH: u64 = hash("");

fn total(xs: list[i64; _]) -> i64 {
    var t: i64 = 0;
    for x in xs {
        t += x;
    }
    return t;
}

fn main() {
    var m: map[str[8], i64; 4] = [];
    m["pear"] = 3;
    m["apple"] = 5;
    m["fig"] = 1;
    let ks: list[str[8]; 4] = keys(m);
    var vs: list[i64; 4] = values(m);
    push(mut vs, 10);
    print(ks, " ", vs, " ", len(m), " ", total(values(m)));
    let none: map[i64, bool; 2] = [];
    print(keys(none), " ", len(values(none)));
    for i, c in variants(Color) {
        print(i, " ", c);
    }
    print(ALL, " ", len(variants(Color)), " ", ALL[2] == Color.Blue);
    print(EMPTY_HASH, " ", hash("a"), " ", hash("foobar"));
    let bytes: list[u8; 8] = [102, 111, 111, 98, 97, 114];
    let arr: [u8; 3] = [97, 98, 99];
    print(hash(bytes) == hash("foobar"), " ", hash(arr) == hash("abc"), " ", hash("abc"[1..]));
}
