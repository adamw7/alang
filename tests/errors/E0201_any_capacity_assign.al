// expect: E0201
// The capacity of a `_` parameter is not known inside the function, so only an empty value is sure to fit.
fn set_name(name: mut str[_], value: str[32]) {
    name = value;
}

fn main() {
    var n: str[64] = "";
    set_name(mut n, "ann");
    print(n);
}
