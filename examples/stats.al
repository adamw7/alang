// Descriptive statistics and a least-squares line. Float results are bit-identical on every
// machine, because ALang only has correctly rounded operations (+ - * /, sqrt, ...) and prints
// floats with at most 6 decimals. Tests compare floats with a tolerance.

const MAX_VALUES: i64 = 64;

struct Summary {
    count: i64,
    smallest: f64,
    largest: f64,
    mean: f64,
    median: f64,
    stddev: f64,
}

struct Line {
    slope: f64,
    intercept: f64,
    r_squared: f64,
}

enum Fit {
    Ok(Line),
    TooFewPoints,
    AllXEqual,
}

fn mean(xs: list[f64; MAX_VALUES]) -> f64
    requires len(xs) > 0
{
    var sum: f64 = 0.0;
    for x in xs {
        sum += x;
    }
    return sum / f64(len(xs));
}

fn sorted(xs: list[f64; MAX_VALUES]) -> list[f64; MAX_VALUES] {
    var out: list[f64; MAX_VALUES] = xs;
    // Insertion sort: move each value left past the larger ones before it.
    for i in 1..len(out) {
        let x: f64 = out[i];
        var j: i64 = i;
        while j > 0 and out[j - 1] > x limit MAX_VALUES {
            out[j] = out[j - 1];
            j -= 1;
        }
        out[j] = x;
    }
    return out;
}

/// Count, smallest, largest, mean, median and (population) standard deviation of 1 to 64 numbers.
tool fn summarize(values: list[f64; MAX_VALUES]) -> Summary
    requires len(values) > 0
    ensures result.smallest <= result.median and result.median <= result.largest
    ensures result.stddev >= 0.0
{
    let m: f64 = mean(values);
    var squares: f64 = 0.0;
    for v in values {
        squares += (v - m) * (v - m);
    }
    let s: list[f64; MAX_VALUES] = sorted(values);
    let n: i64 = len(s);
    var median: f64 = s[n / 2];
    if n % 2 == 0 {
        median = (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }
    return Summary(
        count: n,
        smallest: s[0],
        largest: s[n - 1],
        mean: m,
        median: median,
        stddev: sqrt(squares / f64(n)),
    );
}

/// Fits the line y = slope * x + intercept through the points (xs[i], ys[i]) by least squares.
/// r_squared is the share of the variance in y that the line explains (1.0: a perfect fit).
tool fn fit_line(xs: list[f64; MAX_VALUES], ys: list[f64; MAX_VALUES]) -> Fit
    requires len(xs) == len(ys)
{
    if len(xs) < 2 {
        return Fit.TooFewPoints;
    }
    let mx: f64 = mean(xs);
    let my: f64 = mean(ys);
    var sxx: f64 = 0.0;
    var sxy: f64 = 0.0;
    var syy: f64 = 0.0;
    for i in 0..len(xs) {
        let dx: f64 = xs[i] - mx;
        let dy: f64 = ys[i] - my;
        sxx += dx * dx;
        sxy += dx * dy;
        syy += dy * dy;
    }
    if sxx == 0.0 {
        return Fit.AllXEqual;
    }
    let slope: f64 = sxy / sxx;
    // A constant y lies exactly on the horizontal line through it.
    var r_squared: f64 = 1.0;
    if syy > 0.0 {
        r_squared = sxy * sxy / (sxx * syy);
    }
    return Fit.Ok(Line(slope: slope, intercept: my - slope * mx, r_squared: r_squared));
}

fn close(a: f64, b: f64) -> bool {
    return abs(a - b) < 0.000001;
}

fn main() {
    // Daily high temperatures over two weeks, in degrees Celsius.
    let temps: list[f64; MAX_VALUES] = [
        12.5, 13.0, 14.2, 13.8, 15.1, 16.4, 15.9,
        17.2, 18.0, 17.5, 19.3, 18.8, 20.1, 21.0,
    ];
    print(summarize(temps));

    var days: list[f64; MAX_VALUES] = [];
    for d in 0..len(temps) {
        push(mut days, f64(d));
    }
    match fit_line(days, temps) {
        Fit.Ok(line) => {
            print("trend: ", line.slope, " degrees per day, r^2 = ", line.r_squared);
            print("forecast for day 14: ", line.slope * 14.0 + line.intercept);
        }
        _ => {
            print(fit_line(days, temps));
        }
    }
}

test "a textbook data set" {
    let s: Summary = summarize([2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0]);
    assert s.count == 8;
    assert s.smallest == 2.0 and s.largest == 9.0;
    assert s.mean == 5.0;
    assert s.median == 4.5;
    assert s.stddev == 2.0;
}

test "median of an odd count is the middle value" {
    let s: Summary = summarize([3.0, -1.0, 2.0]);
    assert s.median == 2.0;
    assert close(s.mean, 1.333333);
}

test "decimal fractions are not exact" {
    let s: Summary = summarize([0.1, 0.2]);
    assert s.mean != 0.15;
    assert close(s.mean, 0.15);
}

test "points on a line fit it exactly" {
    match fit_line([0.0, 1.0, 2.0, 3.0], [1.0, 3.0, 5.0, 7.0]) {
        Fit.Ok(line) => {
            assert close(line.slope, 2.0);
            assert close(line.intercept, 1.0);
            assert close(line.r_squared, 1.0);
        }
        _ => {
            panic("expected a line");
        }
    }
}

test "degenerate inputs" {
    assert fit_line([1.0], [1.0]) == Fit.TooFewPoints;
    assert fit_line([2.0, 2.0], [1.0, 5.0]) == Fit.AllXEqual;
    assert fit_line([1.0, 2.0], [3.0, 3.0]) == Fit.Ok(Line(slope: 0.0, intercept: 3.0, r_squared: 1.0));
}
