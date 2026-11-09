tool fn ipv4_to_int(text: str[32]) -> i64 {
    var value: i64 = 0;
    var dots: i64 = 0;
    var digits: i64 = 0;
    var part: i64 = 0;
    for c in text {
        if c == '.' {
            if digits == 0 or dots == 3 {
                return -1;
            }
            value = value * 256 + part;
            dots += 1;
            digits = 0;
            part = 0;
        } else if c >= '0' and c <= '9' {
            if digits == 1 and part == 0 {
                return -1;
            }
            digits += 1;
            if digits > 3 {
                return -1;
            }
            part = part * 10 + i64(c - '0');
            if part > 255 {
                return -1;
            }
        } else {
            return -1;
        }
    }
    if digits == 0 or dots != 3 {
        return -1;
    }
    return value * 256 + part;
}
