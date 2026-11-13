// expect: E0001
test "refused" traps r0006 {
    panic("x");
}

test "unknown" traps R0099 {
    panic("x");
}
