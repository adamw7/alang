// The constants of the number types: i64.MAX, u8.MIN, f64.PI, f64.INFINITY, ... Each has the type it names.
const LIMIT: i64 = i64.MAX - 1;
const HALF_TURN: f64 = f64.TAU / 2.0;

fn smallest(xs: list[i64; _]) -> i64 {
    var best: i64 = i64.MAX;
    for x in xs {
        best = min(best, x);
    }
    return best;
}

fn main() {
    print(i8.MIN, " ", i8.MAX, " ", i16.MIN, " ", i16.MAX, " ", i32.MIN, " ", i32.MAX, " ", i64.MIN, " ", i64.MAX);
    print(u8.MIN, " ", u8.MAX, " ", u16.MAX, " ", u32.MAX, " ", u64.MIN, " ", u64.MAX, " ", LIMIT);
    print(f64.PI, " ", f64.E, " ", HALF_TURN == f64.PI, " ", f64.INFINITY, " ", -f64.INFINITY, " ", is_nan(f64.NAN));
    print(f64.EPSILON == pow(2.0, -52.0), " ", f64.MIN == -f64.MAX, " ", f64.MIN_POSITIVE > 0.0, " ", f64.MAX * 2.0);
    print(f32.PI, " ", f32.EPSILON == pow(f32(2.0), f32(-23.0)), " ", f32.MAX < f32.INFINITY, " ", f64(f32.MAX));
    print(smallest([4, -2, 7]), " ", smallest([]));
    print(checked_add(i64.MAX, 1), " ", wrapping_add(u8.MAX, 1), " ", i64.MIN < i64.MAX);
}
