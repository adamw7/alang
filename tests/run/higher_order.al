// any, all, count_if, find_index, retain and sort_by call a function passed by name; the ones here print, so
// the calls and their order are part of the output that both backends must agree on.
struct Person {
    name: str[8],
    age: i64,
}

fn is_even(x: i64) -> bool {
    return x % 2 == 0;
}

fn noisy_even(x: i64) -> bool {
    print("  is_even(", x, ")");
    return x % 2 == 0;
}

fn adult(p: Person) -> bool {
    return p.age >= 18;
}

fn younger(a: Person, b: Person) -> bool {
    return a.age < b.age;
}

fn noisy_less(a: i64, b: i64) -> bool {
    print("  ", a, " < ", b);
    return a < b;
}

fn longer(a: str[8], b: str[8]) -> bool {
    return len(a) > len(b);
}

fn small(x: i64) -> bool
    requires x >= 0
{
    return x < 10;
}

fn vowel(c: u8) -> bool {
    return contains("aeiou", c);
}

// A function passed on through a parameter of a function type, in a function that takes any type.
fn how_many<T>(xs: list[T; _], f: fn(T) -> bool) -> i64 {
    return count_if(xs, f);
}

fn order<T>(xs: mut list[T; _], before: fn(T, T) -> bool) {
    sort_by(mut xs, before);
}

fn main() {
    let xs: [i64; 6] = [1, 3, 4, 7, 8, 9];
    print(any(xs, noisy_even));
    print(all(xs, noisy_even));
    print(count_if(xs, is_even), " ", find_index(xs, is_even), " ", find_index([1, 3, 5], is_even));
    let none: list[i64; 4] = [];
    print(any(none, is_even), " ", all(none, is_even), " ", count_if(none, is_even), " ", find_index(none, is_even));
    print(count_if(xs[2..5], is_even), " ", find_index(xs[3..], is_even));

    var people: list[Person; 8] = [Person(name: "ann", age: 30), Person(name: "bob", age: 12),
        Person(name: "cy", age: 30), Person(name: "di", age: 5), Person(name: "ed", age: 12)];
    sort_by(mut people, younger);
    print(people);
    retain(mut people, adult);
    print(people);
    retain(mut people, adult);
    print(len(people));

    var ns: list[i64; 8] = [5, 2, 8, 1];
    sort_by(mut ns, noisy_less);
    print(ns);
    var words_by_length: [str[8]; 5] = ["bb", "a", "cccc", "dd", "e"];
    sort_by(mut words_by_length, longer);
    print(words_by_length);

    var digits: list[u8; 16] = [];
    for c in "education" {
        push(mut digits, c);
    }
    retain(mut digits, vowel);
    print(len(digits));
    print(count_if([1, 12, 3, 40], small));

    var mixed: list[i64; 8] = [6, 1, 4, 3];
    print(how_many(mixed, is_even));
    order(mut mixed, noisy_less);
    print(mixed);
}

test "sort_by keeps equal elements in their order" {
    var ps: list[Person; 8] = [Person(name: "a", age: 2), Person(name: "b", age: 1), Person(name: "c", age: 2),
        Person(name: "d", age: 1)];
    sort_by(mut ps, younger);
    assert ps[0].name == "b" and ps[1].name == "d" and ps[2].name == "a" and ps[3].name == "c";
}

test "sort_by agrees with sort" {
    var xs: list[i64; 64] = [];
    var seed: i64 = 7;
    for k in 0..64 {
        seed = (seed * 1103515245 + 12345) % 2147483648;
        push(mut xs, (seed + k) % 100);
    }
    var ys: list[i64; 64] = xs;
    sort_by(mut xs, is_less);
    sort(mut ys);
    assert xs == ys;
}

fn is_less(a: i64, b: i64) -> bool {
    return a < b;
}

test "a precondition of the function passed is checked" traps R0006 {
    count_if([1, -1], small);
}
