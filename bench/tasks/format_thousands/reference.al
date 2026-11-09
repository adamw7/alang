tool fn format_thousands(n: i64) -> str[32] {
    var digits: str[32] = "";
    append(mut digits, n);
    var out: str[32] = "";
    var start: i64 = 0;
    if digits[0] == '-' {
        push(mut out, '-');
        start = 1;
    }
    for i in start..len(digits) {
        if i > start and (len(digits) - i) % 3 == 0 {
            push(mut out, ',');
        }
        push(mut out, digits[i]);
    }
    return out;
}
