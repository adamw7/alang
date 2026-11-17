fn main() {
    var xs: list[u32; 4] = [7];
    print(min(xs), " ", max(xs));
    clear(mut xs);
    print(max(xs));
}
