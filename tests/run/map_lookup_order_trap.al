// m[k] looks k up where the program evaluates it, left to right: a missing key traps before the operands after it,
// in the interpreter and in C, whichever compiler builds it (C leaves the order of a call's arguments open, and a
// lookup left inside the call ran wherever the compiler put it). A value read through a map stays right while the
// map changes around it.
fn pair(a: i64, b: i64) -> i64 {
    return a * 10 + b;
}

fn main() {
    let m: map[i64, i64; 8] = [(1, 5)];
    print(m[1] == 0 and m[2] == 0, " ", m[1] > 0 or m[2] > 0, " ", if m[1] > 0 then m[1] else m[2]);

    var k: i64 = 4;
    var w: map[i64, i64; 8] = [(5, 0)];
    while w[5] < 3 limit 10 {
        w[k] = w[5];
        k -= 1;
        w[5] += 1;
    }
    print(w);

    var names: map[i64, str[8]; 4] = [(5, "five")];
    names[3] = names[5];
    names[7] = names[3];
    print(names);

    let rows: map[i64, list[i64; 2]; 4] = [(1, [7, 8])];
    print(pair(m[1], 2), " ", index_of(rows[1], 8), " ", rows[1][1]);
    print(pair(m[2], m[3]));
}
