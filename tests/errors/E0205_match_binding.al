// expect: E0205
enum Reading {
    Missing,
    Value(i64),
}

fn main() {
    let r: Reading = Reading.Value(3);
    match r {
        Reading.Value(v) => {
            v += 1;
            print(v);
        }
        Reading.Missing => { }
    }
}
