// <, <=, > and >= order values of every type as sort does: structs field by field, enums by variant, tuples, arrays
// and lists element by element, and a type parameter as the type it stands for.
struct Version {
    major: i64,
    minor: i64,
}

enum Level {
    Low,
    Mid(i64),
    High,
}

fn largest<T>(xs: list[T; _]) -> T
    requires len(xs) > 0
{
    var best: T = xs[0];
    for x in xs {
        if x > best {
            best = x;
        }
    }
    return best;
}

fn main() {
    let a: Version = Version(major: 1, minor: 9);
    let b: Version = Version(major: 2, minor: 0);
    print(a < b, " ", a >= b, " ", b > a, " ", a <= a);
    print(Level.Low < Level.Mid(0), " ", Level.Mid(3) < Level.Mid(2), " ", Level.High >= Level.Mid(100));
    print((1, "b") < (1, "c"), " ", [1, 2, 3] < [1, 3, 0], " ", false < true);
    let short: list[i64; 4] = [1, 2];
    let long: list[i64; 4] = [1, 2, 0];
    print(short < long, " ", largest([Version(major: 1, minor: 2), Version(major: 1, minor: 10)]));
    print(largest([Level.Mid(5), Level.Low, Level.Mid(7)]), " ", largest(["pear", "apple"]));
    let o: Option<i64> = Option.Some(3);
    print(Option.None < o, " ", o > Option.Some(2));
}

test "versions order by major, then minor" {
    assert Version(major: 1, minor: 10) > Version(major: 1, minor: 9);
    assert Version(major: 0, minor: 99) < Version(major: 1, minor: 0);
}
