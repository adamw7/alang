// Binary search: bounded `while` loops and contracts.

/// Returns the index of `target` in the sorted array `xs`, or -1 if absent: the builtin binary_search(xs, x),
/// written out.
fn search(xs: [i32; 10], target: i32) -> i64
    requires is_sorted(xs)
    ensures result == -1 or xs[result] == target
{
    var lo: i64 = 0;
    var hi: i64 = len(xs);
    // Each iteration halves [lo, hi), so 64 iterations are always enough.
    while lo < hi limit 64 {
        let mid: i64 = lo + (hi - lo) / 2;
        if xs[mid] == target {
            return mid;
        } else if xs[mid] < target {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return -1;
}

const DATA: [i32; 10] = [-5, 0, 3, 8, 13, 21, 34, 55, 89, 144];

fn main() {
    let targets: [i32; 4] = [13, 144, -5, 7];
    for t in targets {
        print("search ", t, " -> ", search(DATA, t));
    }
}

test "finds every element" {
    for i in 0..len(DATA) {
        assert search(DATA, DATA[i]) == i;
    }
}

test "reports missing elements" {
    assert search(DATA, 4) == -1;
    assert search(DATA, 1000) == -1;
}

test "agrees with the builtin" {
    let targets: [i32; 6] = [-6, -5, 4, 13, 144, 145];
    for t in targets {
        assert search(DATA, t) == binary_search(DATA, t);
    }
}
