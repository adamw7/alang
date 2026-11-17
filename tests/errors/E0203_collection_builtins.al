// expect: E0203
fn main() {
    let names: list[str[8]; 4] = ["b", "a"];
    print(sum("abc"));
    print(min(names));
    print(binary_search("abc", 'b'));
    var fixed: [i64; 3] = [1, 1, 2];
    dedup(mut fixed);
}
