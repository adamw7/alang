// expect: E0403
enum List<T> {
    Nil,
    Cons(T, List<T>),
}

fn main() {
    let l: List<i64> = List.Nil;
    print(l);
}
