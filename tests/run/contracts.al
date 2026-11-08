// requires / ensures are checked on every call.
fn clamp(x: i32, lo: i32, hi: i32) -> i32
    requires lo <= hi
    ensures result >= lo and result <= hi
{
    return max(lo, min(x, hi));
}

fn take(stack: mut list[i32; 4]) -> i32
    requires len(stack) > 0
    ensures len(stack) < 4
{
    return pop(mut stack);
}

fn main() {
    print(clamp(5, 0, 3), " ", clamp(-5, 0, 3), " ", clamp(2, 0, 3));
    var s: list[i32; 4] = [1, 2, 3, 4];
    let top: i32 = take(mut s);
    print(top, " ", s);
}
