// trim_start(s) and trim_end(s) remove ASCII whitespace at one end, as trim(s) does at both.
fn main() {
    let s: str[16] = "  a b \t";
    print("[", trim_start(s), "]");
    print("[", trim_end(s), "]");
    print("[", trim(s), "]");
    let e: str[4] = "   ";
    print("[", trim_start(e), "][", trim_end(e), "]");
}
