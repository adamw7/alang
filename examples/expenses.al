// Sums a CSV export of expenses per category: the kind of everyday data job an agent hands to a tool.
// Amounts are kept in whole cents, so the totals are exact. Run it on a file, or call it as a tool:
//     bin/alang run examples/expenses.al < examples/expenses.in
//     bin/alang call examples/expenses.al summarize '{"csv": "date,category,amount\n2026-01-02,food,12.50"}'
//     bin/alang serve examples/expenses.al    # the same tool over MCP, for Claude Code or any MCP client
const INPUT: i64 = 16384;
const CATEGORIES: i64 = 32;
const BAD_LINES: i64 = 16;

struct Category {
    /// The category, as written in the CSV.
    name: str[32],
    /// The sum of its amounts, in cents.
    cents: i64,
    /// How many rows it has.
    rows: i64,
}

struct Summary {
    /// Every category, the largest total first.
    categories: list[Category; CATEGORIES],
    /// The sum of every counted row, in cents.
    total_cents: i64,
    /// The line numbers (from 1, the header being line 1) of the first rows that were skipped.
    bad_lines: list[i64; BAD_LINES],
    /// How many rows were skipped: not three fields, an amount that is not money, or a new category once
    /// there are 32.
    skipped: i64,
}

/// Parses an amount of money, "12", "12.5", "-3.07" or "1234.50", into cents. More than two decimals, or
/// anything but digits around one '.', is Option.None.
fn parse_cents(text: str[_]) -> Option<i64> {
    let t: str[24] = str[24](trim(text[0..min(len(text), 24)]));
    var sign: i64 = 1;
    var digits: str[24] = t;
    if starts_with(t, "-") {
        sign = -1;
        digits = strip_prefix(t, '-');
    }
    var whole: str[24] = digits;
    var frac: str[24] = "";
    if let Option.Some((w, f)) = split_once(digits, '.') {
        whole = w;
        frac = f;
    }
    if len(whole) == 0 or len(whole) > 15 or len(frac) > 2 {
        return Option.None;
    }
    for c in whole {
        if not is_digit(c) {
            return Option.None;
        }
    }
    for c in frac {
        if not is_digit(c) {
            return Option.None;
        }
    }
    var cents: i64 = i64(whole) * 100;
    if len(frac) == 1 {
        cents += i64(frac) * 10;
    } else if len(frac) == 2 {
        cents += i64(frac);
    }
    return Option.Some(sign * cents);
}

/// Writes cents as money: 1250 as "12.50", -307 as "-3.07".
fn money(cents: i64) -> str[24] {
    var s: str[24] = "";
    if cents < 0 {
        push(mut s, '-');
    }
    append(mut s, abs(cents) / 100, ".", pad_left(abs(cents) % 100, 2, '0'));
    return s;
}

fn larger_first(a: Category, b: Category) -> bool {
    return a.cents > b.cents;
}

/// Sums the amounts of a CSV of expenses per category. The first line is a header; every other line is
/// "date,category,amount", with the amount in units such as "12.50". Blank lines are ignored; other lines
/// that do not parse are counted, with the first 16 line numbers kept, and never stop the rest.
tool fn summarize(csv: str[INPUT]) -> Summary {
    var categories: list[Category; CATEGORIES] = [];
    var total: i64 = 0;
    var bad: list[i64; BAD_LINES] = [];
    var skipped: i64 = 0;
    for i, line in split(csv, '\n') {
        if i == 0 or len(trim(line)) == 0 {
            continue;
        }
        var fields: list[str[64]; 3] = [];
        let fits: bool = try_split(mut fields, line, ',');
        var ok: bool = fits and len(fields) == 3;
        var cents: i64 = 0;
        if ok {
            match parse_cents(fields[2]) {
                Option.Some(c) => { cents = c; }
                Option.None => { ok = false; }
            }
        }
        if ok and len(trim(fields[1])) > 32 {
            ok = false;
        }
        if ok {
            let name: str[32] = str[32](trim(fields[1]));
            var found: bool = false;
            for j in 0..len(categories) {
                if categories[j].name == name {
                    categories[j].cents += cents;
                    categories[j].rows += 1;
                    found = true;
                }
            }
            if not found and len(categories) < cap(categories) {
                push(mut categories, Category(name: name, cents: cents, rows: 1));
                found = true;
            }
            ok = found;
            if found {
                total += cents;
            }
        }
        if not ok {
            skipped += 1;
            if len(bad) < cap(bad) {
                push(mut bad, i + 1);
            }
        }
    }
    sort_by(mut categories, larger_first);
    return Summary(categories: categories, total_cents: total, bad_lines: bad, skipped: skipped);
}

fn main(input: str[INPUT]) {
    let s: Summary = summarize(input);
    for c in s.categories {
        print(pad_right(c.name, 12, ' '), pad_left(money(c.cents), 10, ' '), "  (", c.rows, " rows)");
    }
    print(pad_right("total", 12, ' '), pad_left(money(s.total_cents), 10, ' '));
    if s.skipped > 0 {
        print("skipped ", s.skipped, " line(s): ", s.bad_lines);
    }
}

test "amounts parse to exact cents" {
    assert_eq(parse_cents("12.50"), Option.Some(1250));
    assert_eq(parse_cents(" 7 "), Option.Some(700));
    assert_eq(parse_cents("0.5"), Option.Some(50));
    assert_eq(parse_cents("-3.07"), Option.Some(-307));
    assert_eq(parse_cents("1.234"), Option.None);
    assert_eq(parse_cents("12a"), Option.None);
    assert_eq(parse_cents(""), Option.None);
}

test "money formats cents" {
    assert money(1250) == "12.50";
    assert money(-307) == "-3.07";
    assert money(5) == "0.05";
}

test "sums per category, largest first" {
    let s: Summary = summarize("date,category,amount\n1,food,12.50\n2,rent,800\n3,food,7.25\n");
    assert len(s.categories) == 2;
    assert s.categories[0] == Category(name: "rent", cents: 80000, rows: 1);
    assert s.categories[1] == Category(name: "food", cents: 1975, rows: 2);
    assert s.total_cents == 81975;
    assert s.skipped == 0;
}

test "bad lines are counted, not fatal" {
    let s: Summary = summarize("date,category,amount\n1,food,ten\n\n2,food,1.00\nno commas here\n");
    assert s.total_cents == 100;
    assert s.skipped == 2;
    assert s.bad_lines == [2, 5];
}
