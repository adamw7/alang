fn components(v: str[64]) -> list[i64; 8] {
    var parts: list[i64; 8] = [];
    var current: i64 = 0;
    for c in v {
        if c == '.' {
            push(mut parts, current);
            current = 0;
        } else {
            current = current * 10 + i64(c - '0');
        }
    }
    push(mut parts, current);
    return parts;
}

tool fn compare_versions(a: str[64], b: str[64]) -> i64 {
    let pa: list[i64; 8] = components(a);
    let pb: list[i64; 8] = components(b);
    for i in 0..max(len(pa), len(pb)) {
        var x: i64 = 0;
        var y: i64 = 0;
        if i < len(pa) {
            x = pa[i];
        }
        if i < len(pb) {
            y = pb[i];
        }
        if x < y {
            return -1;
        }
        if x > y {
            return 1;
        }
    }
    return 0;
}
