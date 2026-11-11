// Descriptive statistics and a least-squares line. Float results are bit-identical on every
// machine, because ALang only has correctly rounded operations (+ - * /, sqrt, ...) and prints
// floats with at most 6 decimals. Tests compare floats with a tolerance.

const MAX_VALUES: i64 = 64;
// `summarize` takes values up to this size, so none of its sums, squares or midpoints can overflow:
// 64 squared deviations of at most 2e150 add up to less than 3e302.
const LIMIT: f64 = 1e150;

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
    // The sums behind the fit overflow or underflow an f64: the values are too large, or the x values
    // too close together.
    OutOfRange,
}

// Neither infinite nor NaN: inf - inf and NaN - NaN are NaN, and NaN is not equal to anything.
fn finite(x: f64) -> bool {
    return x - x == 0.0;
}

fn all_equal(xs: list[f64; MAX_VALUES]) -> bool {
    for x in xs {
        if x != xs[0] {
            return false;
        }
    }
    return true;
}

fn within_limit(xs: list[f64; MAX_VALUES]) -> bool {
    for x in xs {
        // Negated so that NaN, which compares false with everything, is rejected too.
        if not (abs(x) <= LIMIT) {
            return false;
        }
    }
    return true;
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

/// Count, smallest, largest, mean, median and (population) standard deviation of 1 to 64 numbers,
/// each between -1e150 and 1e150.
tool fn summarize(values: list[f64; MAX_VALUES]) -> Summary
    requires len(values) > 0
    requires within_limit(values)
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
/// Returns TooFewPoints for fewer than 2 points, AllXEqual when every x is the same, and OutOfRange
/// when the values are too large, or the x values too close together, for f64 arithmetic.
tool fn fit_line(xs: list[f64; MAX_VALUES], ys: list[f64; MAX_VALUES]) -> Fit
    requires len(xs) == len(ys)
{
    if len(xs) < 2 {
        return Fit.TooFewPoints;
    }
    // Compare the inputs rather than test sxx or syy for 0: the mean of equal values can be off in
    // its last bit, which leaves tiny nonzero deviations from it.
    if all_equal(xs) {
        return Fit.AllXEqual;
    }
    // A constant y lies exactly on the horizontal line through it.
    if all_equal(ys) {
        return Fit.Ok(Line(slope: 0.0, intercept: ys[0], r_squared: 1.0));
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
    let slope: f64 = sxy / sxx;
    let intercept: f64 = my - slope * mx;
    // sxy * sxy / (sxx * syy), divided before multiplying so that it overflows later.
    let r_squared: f64 = slope * (sxy / syy);
    // An overflowed sum can still give finite results (5e199 / inf is 0), so check the sums too.
    // JSON has no NaN or infinity, so returning one would fail the whole call.
    if not (finite(sxx) and finite(sxy) and finite(syy) and finite(slope) and finite(intercept) and finite(r_squared)) {
        return Fit.OutOfRange;
    }
    return Fit.Ok(Line(slope: slope, intercept: intercept, r_squared: r_squared));
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
    let fit: Fit = fit_line(days, temps);
    match fit {
        Fit.Ok(line) => {
            print("trend: ", line.slope, " degrees per day, r^2 = ", line.r_squared);
            print("forecast for day 14: ", line.slope * 14.0 + line.intercept);
        }
        _ => {
            print(fit);
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

test "equal values are found even when their mean is inexact" {
    // The mean of three 0.1s is 0.10000000000000002, so the deviations from it are not 0.
    assert fit_line([0.1, 0.1, 0.1], [1.0, 5.0, 2.0]) == Fit.AllXEqual;
    assert fit_line([1.0, 2.0, 3.0], [0.1, 0.1, 0.1]) == Fit.Ok(Line(slope: 0.0, intercept: 0.1, r_squared: 1.0));
}

test "a fit that does not fit in an f64 is reported" {
    // The squared deviations overflow to infinity.
    assert fit_line([1e200, 2e200], [1.0, 2.0]) == Fit.OutOfRange;
    // The squared deviations underflow to 0.
    assert fit_line([0.0, 1e-200], [0.0, 1.0]) == Fit.OutOfRange;
}

test "values up to the limit give finite results" {
    let s: Summary = summarize([-LIMIT, LIMIT, LIMIT, -LIMIT]);
    assert s.mean == 0.0 and s.median == 0.0 and s.stddev == LIMIT;
    assert within_limit([LIMIT, -LIMIT]);
    assert not within_limit([1e151]);
    assert not within_limit([0.0 / 0.0]);
}
