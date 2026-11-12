// expect: E0201
struct Person {
    name: str[16],
}

fn person(name: str[_]) -> Person {
    return Person(name: name);
}

fn main() {
    print(person("ann"));
}
