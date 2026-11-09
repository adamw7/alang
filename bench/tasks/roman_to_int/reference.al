fn value(c: u8) -> i64 {
    var v: i64 = 0;
    match c {
        'I' => { v = 1; }
        'V' => { v = 5; }
        'X' => { v = 10; }
        'L' => { v = 50; }
        'C' => { v = 100; }
        'D' => { v = 500; }
        'M' => { v = 1000; }
        _ => { }
    }
    return v;
}

tool fn roman_to_int(s: str[15]) -> i64 {
    let n: i64 = len(s);
    var total: i64 = 0;
    for i in 0..n {
        let v: i64 = value(s[i]);
        if i + 1 < n and value(s[i + 1]) > v {
            total -= v;
        } else {
            total += v;
        }
    }
    return total;
}
