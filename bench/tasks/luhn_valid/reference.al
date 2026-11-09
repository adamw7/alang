tool fn luhn_valid(number: str[32]) -> bool {
    let n: i64 = len(number);
    if n < 2 {
        return false;
    }
    var total: i64 = 0;
    for k in 0..n {
        let c: u8 = number[n - 1 - k];
        if c < '0' or c > '9' {
            return false;
        }
        var d: i64 = i64(c - '0');
        if k % 2 == 1 {
            d *= 2;
            if d > 9 {
                d -= 9;
            }
        }
        total += d;
    }
    return total % 10 == 0;
}
