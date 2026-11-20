// min_by and max_by of an empty list trap.
fn later(a: str[4], b: str[4]) -> bool {
    return a > b;
}

fn main() {
    let some: list[str[4]; 4] = ["b", "c", "a"];
    let none: list[str[4]; 4] = [];
    print(max_by(some, later), min_by(some, later));
    print(min_by(none, later));
}
