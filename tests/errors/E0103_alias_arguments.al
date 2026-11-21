// expect: E0103
type Pair<T> = (T, T);

fn main() {
    let p: Pair = (1, 2);
    print(p);
}
