// Tallies "name points" lines from the standard input and ranks the names, with the map, the parser and
// the padding of helpers.al, imported instead of copied. Run it on a file, or on your own input:
//     bin/alang run examples/tally.al < examples/tally.in
import "helpers.al";

const INPUT: i64 = 1024;

struct Total {
    points: i64,
    name: str[TEXT],
}

struct Tally {
    /// Each name with its points, most points first.
    ranking: list[Total; ITEMS],
    /// How many lines were not a name and a whole number.
    skipped: i64,
}

/// Adds up the points of each name in `text`, one "name points" pair per line, and ranks the names, most
/// points first (a tie puts the name that sorts last first). Blank lines are ignored, and other lines that
/// are not a name and a whole number are counted as skipped. At most 64 lines of 64 bytes, 16 names.
tool fn tally(text: str[INPUT]) -> Tally {
    var points: list[Entry; ITEMS] = [];
    var skipped: i64 = 0;
    let lines: list[str[TEXT]; 64] = split(text, '\n');
    for line in lines {
        let parts: list[str[TEXT]; 8] = words(line);
        if len(parts) == 0 {
            continue;
        }
        var counted: bool = false;
        if len(parts) == 2 {
            match parse_i64(parts[1]) {
                ParsedI64.Ok(n) => {
                    var sum: i64 = n;
                    match lookup(points, parts[0]) {
                        MaybeI64.Some(before) => { sum += before; }
                        MaybeI64.None => { }
                    }
                    store(mut points, parts[0], sum);
                    counted = true;
                }
                ParsedI64.Bad => { }
            }
        }
        if not counted {
            skipped += 1;
        }
    }
    var ranking: list[Total; ITEMS] = [];
    for e in points {
        push(mut ranking, Total(points: e.value, name: e.key));
    }
    sort(mut ranking);
    reverse(mut ranking);
    return Tally(ranking: ranking, skipped: skipped);
}

fn main(input: str[INPUT]) {
    let result: Tally = tally(input);
    for t in result.ranking {
        var score: str[TEXT] = "";
        append(mut score, t.points);
        print(pad_left(score, 6, ' '), "  ", t.name);
    }
    if result.skipped > 0 {
        print(result.skipped, " line(s) skipped");
    }
}

test "points add up per name, most first" {
    let t: Tally = tally("ann 7\nbob 12\nann 6\n");
    assert len(t.ranking) == 2;
    assert t.ranking[0] == Total(points: 13, name: "ann");
    assert t.ranking[1] == Total(points: 12, name: "bob");
    assert t.skipped == 0;
}

test "lines that are not a name and a number are skipped" {
    let t: Tally = tally("ann 7\nbob twelve\njust-a-name\n\ncy 1 2\n");
    assert len(t.ranking) == 1;
    assert t.skipped == 3;
}
