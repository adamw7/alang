fn main() {
    let sep: str[4] = "";
    print("before");
    for p in split("a,b", sep) {
        print(p);
    }
}
