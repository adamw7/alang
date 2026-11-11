fn main() {
    let parts: [str[4]; 3] = ["abc", "def", "gh"];
    let ok: str[10] = join(parts, ",");
    print(ok);
    let short: str[9] = join(parts, ",");
    print(short);
}
