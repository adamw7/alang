tool fn fnv1a_32(text: str[64]) -> i64 {
    var h: u64 = 2166136261;
    for b in text {
        h = h ^ u64(b);
        h = (h * 16777619) % 4294967296;
    }
    return i64(h);
}
