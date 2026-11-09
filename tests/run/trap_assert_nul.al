// The message of an assert is kept whole: the C backend used to cut it at the NUL byte.
fn main() {
    let ready: bool = false;
    assert ready, "not\0ready \xff";
}
