// expect: E0201 E0211 E0306 E0307
// A guard is a bool condition; a guarded arm covers nothing, and an arm after one that takes the same values
// unguarded is unreachable.
fn kind(x: i64) -> i64 {
    var k: i64 = 0;
    match x {
        0 if x => { k = 1; }
        0 => { k = 2; }
        0 if x > 5 => { k = 3; }
        _ if x > 9 => { k = 4; }
    }
    return if k > 0 then k else "none";
}

fn main() {
    print(kind(1));
}
