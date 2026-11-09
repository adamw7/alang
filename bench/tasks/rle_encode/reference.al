tool fn rle_encode(text: str[64]) -> str[128] {
    let n: i64 = len(text);
    var out: str[128] = "";
    var i: i64 = 0;
    while i < n limit 64 {
        let c: u8 = text[i];
        var run: i64 = 0;
        while i < n and text[i] == c limit 64 {
            run += 1;
            i += 1;
        }
        append(mut out, run);
        push(mut out, c);
    }
    return out;
}
