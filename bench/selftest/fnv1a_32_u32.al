// The same mistake in ALang: the u32 multiplication overflows and traps (R0001).
tool fn fnv1a_32(text: str[64]) -> i64 {
    var h: u32 = 2166136261;
    for b in text {
        h = h ^ u32(b);
        h = h * 16777619;
    }
    return i64(h);
}
