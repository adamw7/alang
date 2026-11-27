// expect: E0217
// A test that calls a tool whose parameter has a `_` capacity once crashed the checker: the call has no capacity
// for it.
tool fn f(xs: list[i64; _]) -> i64 {
    return 0;
}

test "calls it" {
    assert f([1]) == 0;
}
