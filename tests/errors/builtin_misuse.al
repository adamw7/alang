// expect: E0101 E0201 E0203 E0204 E0205 E0207 E0215
// Each builtin misused once; builtin_misuse.err pins every message and hint.
fn nothing() { }

fn main() {
    var fixed: [i64; 3] = [1, 2, 3];
    var s: str[8] = "";
    var n: i64 = 0;
    let u: u8 = 1;
    let frozen: list[i64; 2] = [];
    let a: i64 = len();
    let b: i64 = len(n);
    push(mut fixed, 4);
    push(mut frozen, 4);
    push(mut s);
    clear(n);
    let c: i64 = pop(mut missing);
    append(mut s);
    append(mut n, "x");
    let d: i64 = min(n, 1.5);
    let e: i64 = max(1);
    let f: u8 = abs(u);
    let g: f64 = abs(unknown);
    let h: f64 = sqrt(n);
    print(nothing());
    let v: i64 = nothing();
    print(len(mut s));
    panic();
}
