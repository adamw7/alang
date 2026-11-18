fn padded(s: str[_], width: i64) -> str[32] {
    return pad_right(s, width, '.');
}
