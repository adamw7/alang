// A failed `requires` of main is reported at its name, by the interpreter and by the C backend
// (whose generated C used to call main without the call-site arguments and did not compile).
fn main() requires 1 > 2 {
    print("never printed");
}
