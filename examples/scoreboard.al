// Scores per player on the collection builtins: a map for the totals, and fold, transform, retain, zip,
// extend and the ordered-map lookups in place of hand-written loops.
const PLAYERS: i64 = 8;

struct Round {
    name: str[8],
    points: i64,
}

fn points_of(r: Round) -> i64 {
    return r.points;
}

fn add(total: i64, x: i64) -> i64 {
    return total + x;
}

fn is_positive(x: i64) -> bool {
    return x > 0;
}

fn doubled(x: i64) -> i64 {
    return x * 2;
}

/// Adds up the points of each player: one total per player, in name order.
tool fn totals(results: list[Round; 16]) -> list[i64; PLAYERS] {
    var by_name: map[str[8], i64; PLAYERS] = [];
    for r in results {
        by_name[r.name] = get_or(by_name, r.name, 0) + r.points;
    }
    return values(by_name);
}

/// The sum of the positive points, doubled, and how many results there were.
tool fn bonus(results: list[Round; 16]) -> (i64, i64) {
    var scored: list[i64; 16] = transform(results, points_of);
    retain(mut scored, is_positive);
    let doubled_points: list[i64; 16] = transform(scored, doubled);
    return (fold(doubled_points, 0, add), len(results));
}

/// The player at or below `limit` in name order, or "" when there is none.
tool fn last_up_to(names: list[str[8]; PLAYERS], limit: str[8]) -> str[8] {
    var seen: set[str[8]; PLAYERS] = [];
    for n in names {
        insert(mut seen, n);
    }
    match floor_key(seen, limit) {
        Option.Some(n) => { return n; }
        Option.None => { return ""; }
    }
}

fn main() {
    let rs: list[Round; 16] = [
        Round(name: "ann", points: 7),
        Round(name: "bob", points: 12),
        Round(name: "ann", points: -2),
    ];
    print(totals(rs));
    print(bonus(rs));
    var all: list[i64; 8] = [1, 2];
    extend(mut all, [3, 4]);
    for (a, b) in zip(all, transform(all, doubled)) {
        print(a, " -> ", b);
    }
    print(last_up_to(["cy", "ann", "bob"], "bz"));
}

test "points add up per player in name order" {
    let rs: list[Round; 16] = [Round(name: "bob", points: 1), Round(name: "ann", points: 5), Round(name: "bob", points: 2)];
    assert totals(rs) == [5, 3];
}

test "only positive points earn a bonus" {
    let rs: list[Round; 16] = [Round(name: "a", points: 3), Round(name: "b", points: -4)];
    assert bonus(rs) == (6, 2);
}

test "last_up_to finds the floor key" {
    assert last_up_to(["cy", "ann"], "b") == "ann";
    assert last_up_to(["cy"], "b") == "";
}
