// expect: E0404
// Nesting is counted through calls: each call below is 4 levels deep in its function
// (body, return, +, call), so 32 calls in a row reach 131 levels, more than 128.
fn g0() -> i64 { return g1() + 1; }
fn g1() -> i64 { return g2() + 1; }
fn g2() -> i64 { return g3() + 1; }
fn g3() -> i64 { return g4() + 1; }
fn g4() -> i64 { return g5() + 1; }
fn g5() -> i64 { return g6() + 1; }
fn g6() -> i64 { return g7() + 1; }
fn g7() -> i64 { return g8() + 1; }
fn g8() -> i64 { return g9() + 1; }
fn g9() -> i64 { return g10() + 1; }
fn g10() -> i64 { return g11() + 1; }
fn g11() -> i64 { return g12() + 1; }
fn g12() -> i64 { return g13() + 1; }
fn g13() -> i64 { return g14() + 1; }
fn g14() -> i64 { return g15() + 1; }
fn g15() -> i64 { return g16() + 1; }
fn g16() -> i64 { return g17() + 1; }
fn g17() -> i64 { return g18() + 1; }
fn g18() -> i64 { return g19() + 1; }
fn g19() -> i64 { return g20() + 1; }
fn g20() -> i64 { return g21() + 1; }
fn g21() -> i64 { return g22() + 1; }
fn g22() -> i64 { return g23() + 1; }
fn g23() -> i64 { return g24() + 1; }
fn g24() -> i64 { return g25() + 1; }
fn g25() -> i64 { return g26() + 1; }
fn g26() -> i64 { return g27() + 1; }
fn g27() -> i64 { return g28() + 1; }
fn g28() -> i64 { return g29() + 1; }
fn g29() -> i64 { return g30() + 1; }
fn g30() -> i64 { return g31() + 1; }
fn g31() -> i64 { return 0; }
fn main() {
    print(g0());
}
