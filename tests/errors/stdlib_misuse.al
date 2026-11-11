// expect: E0102 E0108 E0201 E0203 E0204 E0207 E0212 E0214 E0215 E0216
// Misused standard library functions: each diagnostic names the call to write instead.
fn trim(s: str[8]) -> str[8] {
    return s;
}

fn main() {
    let s: str[16] = "a,b";
    var xs: list[i64; 4] = [3, 1];
    print(split(s, ","));
    let n: str[8] = split(s, ",");
    let parts: list[i64; 4] = split(s, ",");
    let line: str[16] = join(xs, ",");
    let digit: bool = is_digit(len(s));
    let has: bool = contains(xs, "a");
    let num: bool = contains(42, 4);
    sort(xs);
    let last: i64 = remove(mut xs, 1.5);
    print(remove(mut xs, 0));
    let p: i64 = pow(2, 1.5);
    let g: i64 = gcd(i32(4), i64(6));
    let f: f64 = f64("1.5");
    let c: i64 = clamp(1, 2);
    let w: str[16] = substr(s, 0, 1);
    let q: bool = starts_with(xs, 3);
    let r: str[16] = replace(s, 1.5, "x");
    var ws: list[str[8]; 4] = [];
    ws = words(s);
    ws = split(s);
}
