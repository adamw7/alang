// Tallies "name points" lines from the standard input and ranks the names, with the map and the parser of
// helpers.al, imported instead of copied. Run it on a file, or on your own input:
//     bin/alang run examples/tally.al < examples/tally.in
import "helpers.al";

const INPUT: i64 = 1024;
const ITEMS: i64 = 16;

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
/// points first (a tie puts the name that sorts last first). Blank lines are ignored; lines that are not a
/// name of up to 64 bytes and a whole number, and lines with a new name once there are 16, are counted as
/// skipped.
tool fn tally(text: str[INPUT]) -> Tally {
    var points: list[Entry; ITEMS] = [];
    var skipped: i64 = 0;
    // Line by line, and with try_words, so that no input traps: a line of three words makes `fits` false.
    for line in split(text, '\n') {
        var parts: list[str[TEXT]; 2] = [];
        let fits: bool = try_words(mut parts, line);
        if fits and len(parts) == 0 {
            continue;
        }
        var counted: bool = false;
        if fits and len(parts) == 2 {
            match parse_i64(parts[1]) {
                ParsedI64.Ok(n) => {
                    match lookup(points, parts[0]) {
                        MaybeI64.Some(before) => {
                            store(mut points, parts[0], before + n);
                            counted = true;
                        }
                        MaybeI64.None => {
                            if len(points) < ITEMS {
                                store(mut points, parts[0], n);
                                counted = true;
                            }
                        }
                    }
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
        print(pad_left(t.points, 6, ' '), "  ", t.name);
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
