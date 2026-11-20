// min_by, max_by, swap, pop_front and assert_eq.
struct Person {
    name: str[8],
    age: i64,
}

fn younger(a: Person, b: Person) -> bool {
    return a.age < b.age;
}

fn shorter(a: str[8], b: str[8]) -> bool {
    return len(a) < len(b);
}

fn main() {
    let people: list[Person; 4] = [Person(name: "ann", age: 30), Person(name: "bob", age: 20), Person(name: "cy", age: 20),
        Person(name: "di", age: 40)];
    // The first of the youngest, and the first of the oldest.
    print(min_by(people, younger).name, " ", max_by(people, younger).name);
    let words: [str[8]; 4] = ["ccc", "a", "bb", "ddd"];
    print(min_by(words, shorter), " ", max_by(words, shorter));
    var youngest: Person = min_by(people, younger);
    youngest.age += 1;
    print(youngest, " ", people[1]);
    var xs: list[i64; 4] = [1, 2, 3];
    swap(mut xs, 0, 2);
    swap(mut xs, 1, 1);
    var grid: [[i64; 2]; 2] = [[1, 2], [3, 4]];
    swap(mut grid, 0, 1);
    swap(mut grid[0], 0, 1);
    var s: str[8] = "abcd";
    swap(mut s, 0, 3);
    var a: str[8] = "left";
    var b: str[8] = "right";
    swap(mut a, mut b);
    var p: Person = people[0];
    var q: Person = people[3];
    swap(mut p.age, mut q.age);
    print(xs, " ", grid, " ", s, " ", a, " ", b, " ", p.age, " ", q.age);
    // A queue: push at the back, pop_front at the front.
    var queue: list[i64; 4] = [10];
    push(mut queue, 20);
    let first: i64 = pop_front(mut queue);
    push(mut queue, 30);
    let second: i64 = pop_front(mut queue);
    var text: str[8] = "xyz";
    let c: u8 = pop_front(mut text);
    print(first, " ", second, " ", queue, " ", c, " ", text);
    assert_eq(first, 10);
    assert_eq(text, "yz");
    assert_eq(queue, [30]);
    print("ok");
}

test "assert_eq shows both values" traps R0005 {
    assert_eq(1 + 1, 3);
}
