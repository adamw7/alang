tool fn parse_duration(text: str[32]) -> i64 {
    let n: i64 = len(text);
    if n == 0 {
        return -1;
    }
    var total: i64 = 0;
    var last: i64 = -1;
    var i: i64 = 0;
    while i < n limit 32 {
        var value: i64 = 0;
        var digits: i64 = 0;
        while i < n and text[i] >= '0' and text[i] <= '9' limit 32 {
            if digits < 6 {
                value = value * 10 + i64(text[i] - '0');
            }
            digits += 1;
            i += 1;
        }
        if digits < 1 or digits > 6 or i >= n {
            return -1;
        }
        var rank: i64 = -1;
        var scale: i64 = 0;
        match text[i] {
            'h' => { rank = 0; scale = 3600; }
            'm' => { rank = 1; scale = 60; }
            's' => { rank = 2; scale = 1; }
            _ => { return -1; }
        }
        if rank <= last {
            return -1;
        }
        last = rank;
        total += value * scale;
        i += 1;
    }
    return total;
}
