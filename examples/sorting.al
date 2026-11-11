// Sorting without recursion: a bottom-up merge sort, and a quicksort that keeps the ranges
// still to sort on an explicit stack. Contracts check every result is sorted, and the tests use
// a seeded generator, since ALang has no randomness.

const N: i64 = 32;

struct Range {
    lo: i64,
    hi: i64,
}

fn is_sorted(xs: list[i64; N]) -> bool {
    for i in 1..len(xs) {
        if xs[i - 1] > xs[i] {
            return false;
        }
    }
    return true;
}

fn swap(xs: mut list[i64; N], i: i64, j: i64) {
    let t: i64 = xs[i];
    xs[i] = xs[j];
    xs[j] = t;
}

/// Sorts up to 32 integers in ascending order (a stable merge sort).
tool fn merge_sort(input: list[i64; N]) -> list[i64; N]
    ensures len(result) == len(input)
    ensures is_sorted(result)
{
    let n: i64 = len(input);
    var src: list[i64; N] = input;
    var dst: list[i64; N] = input;
    // Merge neighboring sorted runs of `width` items into runs of 2 * width, until one run is left.
    var width: i64 = 1;
    // `width` doubles every pass and stays below N = 2^5.
    while width < n limit 5 {
        for pair in 0..(n + 2 * width - 1) / (2 * width) {
            let lo: i64 = pair * 2 * width;
            let mid: i64 = min(lo + width, n);
            let hi: i64 = min(lo + 2 * width, n);
            var i: i64 = lo;
            var j: i64 = mid;
            for k in lo..hi {
                // Take from the left run on ties: that keeps equal items in order (stable).
                if i < mid and (j >= hi or src[i] <= src[j]) {
                    dst[k] = src[i];
                    i += 1;
                } else {
                    dst[k] = src[j];
                    j += 1;
                }
            }
        }
        src = dst;
        width *= 2;
    }
    return src;
}

/// Sorts up to 32 integers in ascending order (quicksort).
tool fn quicksort(input: list[i64; N]) -> list[i64; N]
    ensures len(result) == len(input)
    ensures is_sorted(result)
{
    var xs: list[i64; N] = input;
    var pending: list[Range; N] = [Range(lo: 0, hi: len(xs))];
    // Each partition puts one pivot in its final place and only ranges of 2 or more items are
    // pushed, so there are at most N partitions after the first pop.
    while len(pending) > 0 limit N + 1 {
        let r: Range = pop(mut pending);
        if r.hi - r.lo >= 2 {
            // Lomuto partition around the middle item, moved to the end first.
            swap(mut xs, r.lo + (r.hi - r.lo) / 2, r.hi - 1);
            let pivot: i64 = xs[r.hi - 1];
            var store: i64 = r.lo;
            for i in r.lo..r.hi - 1 {
                if xs[i] < pivot {
                    swap(mut xs, i, store);
                    store += 1;
                }
            }
            swap(mut xs, store, r.hi - 1);
            if store - r.lo >= 2 {
                push(mut pending, Range(lo: r.lo, hi: store));
            }
            if r.hi - (store + 1) >= 2 {
                push(mut pending, Range(lo: store + 1, hi: r.hi));
            }
        }
    }
    return xs;
}

/// `count` pseudo-random numbers from 0 to 999. The same seed always gives the same numbers.
fn random_list(seed: i64, count: i64) -> list[i64; N]
    requires 0 <= seed and seed < 65537
    requires count <= N
{
    var out: list[i64; N] = [];
    var x: i64 = seed;
    for i in 0..count {
        // A small linear congruential generator: x stays below 65537, so nothing overflows.
        x = (x * 75 + 74) % 65537;
        push(mut out, x % 1000);
    }
    return out;
}

fn main() {
    let xs: list[i64; N] = random_list(42, 12);
    print("input:      ", xs);
    print("merge sort: ", merge_sort(xs));
    print("quicksort:  ", quicksort(xs));
}

test "small and edge cases" {
    assert merge_sort([]) == [];
    assert quicksort([]) == [];
    assert merge_sort([1]) == [1];
    assert quicksort([2, 1]) == [1, 2];
    assert merge_sort([3, -1, 2, -1, 0]) == [-1, -1, 0, 2, 3];
    assert quicksort([3, -1, 2, -1, 0]) == [-1, -1, 0, 2, 3];
}

test "already sorted, reversed and constant inputs" {
    var up: list[i64; N] = [];
    var down: list[i64; N] = [];
    for i in 0..N {
        push(mut up, i);
        push(mut down, N - i - 1);
    }
    assert merge_sort(down) == up;
    assert quicksort(down) == up;
    assert quicksort(up) == up;
    let same: list[i64; N] = [7; 32];
    assert quicksort(same) == same;
}

test "both sorts agree on many pseudo-random inputs" {
    for seed in 0..50 {
        let xs: list[i64; N] = random_list(seed, seed % 33);
        let a: list[i64; N] = merge_sort(xs);
        assert a == quicksort(xs);
        // Sorting keeps the items: the sums match.
        var before: i64 = 0;
        var after: i64 = 0;
        for i in 0..len(xs) {
            before += xs[i];
            after += a[i];
        }
        assert before == after;
    }
}
