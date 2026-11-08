// Unary operators on variables (regression: -n must stay an integer).
const N: i64 = 4;
const SIZE: i64 = -N * N + 1;

fn main() {
    let n: i64 = 4;
    let f: f64 = 2.5;
    let small: i8 = -127;
    print(-n * n + 1, " ", SIZE, " ", -f, " ", -(-n), " ", -small, " ", ~n, " ", not (n > 3));
    let neg_one: f32 = -1.0;
    print(-neg_one, " ", -(n - 10));
}
