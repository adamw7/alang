// fold, transform and zip, with functions passed by name, passed on, and with requires.
import "lib/units.al" as units;

struct Stats {
    count: i64,
    total: i64,
    longest: str[8],
}

fn tally(acc: Stats, word: str[8]) -> Stats {
    return Stats(count: acc.count + 1, total: acc.total + len(word), longest: if len(word) > len(acc.longest) then word else acc.longest);
}

fn add(acc: i64, x: i64) -> i64 {
    return acc + x;
}

fn digits(acc: str[16], x: i64) -> str[16] {
    var s: str[16] = acc;
    append(mut s, x);
    return s;
}

fn square(x: i64) -> i64 {
    return x * x;
}

fn halve(x: i64) -> f64
    requires x >= 0
{
    return f64(x) / 2.0;
}

fn shout(w: str[8]) -> str[8] {
    return to_upper(w);
}

fn identity(w: str[8]) -> str[8] {
    return w;
}

fn sizes(w: str[8]) -> Stats {
    return Stats(count: 1, total: len(w), longest: w);
}

// A function passed on through a parameter of a function type.
fn apply_all(xs: list[i64; 4], f: fn(i64) -> i64) -> list[i64; 4] {
    return transform(xs, f);
}

fn total_with(xs: [i64; 3], f: fn(i64, i64) -> i64) -> i64 {
    return fold(xs, 0, f);
}

fn main() {
    let words: list[str[8]; 4] = ["fig", "banana", "kiwi"];
    print(fold(words, Stats(count: 0, total: 0, longest: ""), tally));
    let nums: [i64; 3] = [3, 1, 4];
    let total: i64 = fold(nums, 100, add);
    let empty: list[i64; 4] = [];
    print(total, " ", fold(empty, 7, add), " ", fold(nums, "", digits), " ", total_with(nums, add));

    // transform: a list keeps its capacity and length, an array its length.
    let squares: [i64; 3] = transform(nums, square);
    print(squares, " ", transform(nums, halve), " ", transform(empty, square), " ", apply_all([1, 2, 3], square));
    let loud: list[str[8]; 4] = transform(words, shout);
    var same: list[str[8]; 4] = transform(words, identity);
    same[0] = "date";
    print(loud, " ", same, " ", words, " ", transform(words, sizes)[1]);
    print(transform(nums, units.label));

    // zip: as far as the shorter goes; a list of the smaller capacity.
    let prices: list[f64; 8] = [1.5, 0.25, 2.0, 9.0];
    for (w, p) in zip(words, prices) {
        print(w, " ", p);
    }
    let pairs: list[(i64, str[8]); 3] = zip(nums, words);
    print(pairs, " ", len(zip(empty, nums)), " ", zip(nums, [true, false, true]));
    for i, (n, w) in zip(nums, words) {
        print(i, ": ", n, " ", w);
    }
}
