tool fn mul_mod(a: i64, b: i64, m: i64) -> i64
    requires m >= 1 and m <= 4611686018427387904
    requires a >= 0 and a < m and b >= 0 and b < m
{
    var result: i64 = 0;
    var x: i64 = a;
    var y: i64 = b;
    while y > 0 limit 64 {
        if y % 2 == 1 {
            result = (result + x) % m;
        }
        x = (x + x) % m;
        y = y / 2;
    }
    return result;
}
