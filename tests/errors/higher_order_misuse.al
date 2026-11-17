// expect: E0201 E0203 E0207
fn is_even(x: i64) -> bool {
    return x % 2 == 0;
}

fn shorter(a: str[8], b: str[8]) -> bool {
    return len(a) < len(b);
}

fn main() {
    let xs: list[i64; 4] = [1, 2];
    var fixed: [i64; 3] = [3, 1, 2];
    var names: list[str[16]; 4] = ["a"];
    print(any(xs, 3));
    print(all("abc", is_even));
    print(count_if(xs, abs));
    print(find_index(xs, shorter));
    retain(mut fixed, is_even);
    sort_by(xs, shorter);
    sort_by(mut names, shorter);
    print(checked_add(i32(1), u8(2)));
    print(mul_mod(1, 2, 3.0));
    print(parse_i64(12));
}
