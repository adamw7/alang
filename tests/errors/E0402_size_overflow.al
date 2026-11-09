// expect: E0402
// max-stack: 1000000
// 2^63 bytes: the computed size must saturate, not wrap around to 0 and slip under the budget.
// (A tool, not main, so that suites which run main never try to allocate it.)

/// Needs more memory than any machine has.
tool fn huge() -> i64 {
    let big: [[[i64; 1048576]; 1048576]; 1048576] = [[[0; 1048576]; 1048576]; 1048576];
    return len(big);
}
