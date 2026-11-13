// A test written `traps CODE` passes only when its body stops with that trap.
fn half(x: i64) -> i64
    requires x % 2 == 0
{
    return x / 2;
}

fn fill(n: i64) -> list[i64; 3] {
    var xs: list[i64; 3] = [];
    for i in 0..n {
        push(mut xs, i);
    }
    return xs;
}

fn main() {
    print(half(10), " ", fill(3));
}

test "an even number halves" {
    assert half(8) == 4;
}

test "an odd number is refused" traps R0006 {
    half(3);
}

test "a fourth element does not fit" traps R0004 {
    let xs: list[i64; 3] = fill(4);
    print(xs);
}

test "a failed assert is a trap too" traps R0005 {
    assert half(2) == 2;
}

test "the trap may come from deep in a loop" traps R0002 {
    var total: i64 = 0;
    for i in rev 0..5 {
        total += 10 / i;
    }
}

test "a panic" traps R0010 {
    panic("stop");
}
