// A for or while loop gives a value as the whole value of a let, var, assignment or return:
// the value of the `break v;` that leaves it, or the value after `else` when it ends without one.
fn index_of_zero(xs: list[i64; 8]) -> i64 {
    return for i, x in xs {
        if x == 0 {
            break i;
        }
    } else -1;
}

fn find_in_grid(g: [[i64; 3]; 3], want: i64) -> (i64, i64) {
    // A break with a value leaves the innermost loop that gives one, and the loops inside it.
    let at: (i64, i64) = for r in 0..3 {
        for c in 0..3 {
            if g[r][c] == want {
                break (r, c);
            }
        }
    } else (-1, -1);
    return at;
}

fn first_word(text: str[64]) -> str[16] {
    let w: str[16] = for word in words(text) {
        if len(word) > 3 and len(word) <= 16 {
            break str[16](word);
        }
    } else "none";
    return w;
}

fn main() {
    print(index_of_zero([3, 1, 0, 4]), " ", index_of_zero([1, 2]));
    let g: [[i64; 3]; 3] = [[1, 2, 3], [4, 5, 6], [7, 8, 9]];
    print(find_in_grid(g, 6), " ", find_in_grid(g, 10));
    print(first_word("a an the quick fox"), " ", first_word("a b"));
    var n: i64 = 27;
    var steps: i64 = 0;
    steps = while true limit 1000 {
        if n == 1 {
            break steps;
        }
        n = if n % 2 == 0 then n / 2 else 3 * n + 1;
        steps += 1;
    } else -1;
    print(steps);
    // A return inside one leaves the function, and continue goes on with the loop around.
    var found: list[i64; 4] = [];
    for k in 1..5 {
        let sq: i64 = for j in 0..10 {
            if j * j == k {
                break j;
            }
            if j > k {
                break -1;
            }
        } else -2;
        if sq < 0 {
            continue;
        }
        push(mut found, sq);
    }
    print(found, " ", early(3), " ", early(0));
}

fn early(limit: i64) -> i64 {
    let v: i64 = for i in 0..limit {
        if i == 1 {
            return 100;
        }
    } else 7;
    return v;
}

test "break inside a nested plain loop leaves only that loop" {
    let x: i64 = for i in 0..3 {
        for j in 0..3 {
            if j == 1 {
                break;
            }
        }
        if i == 2 {
            break i * 10;
        }
    } else 0;
    assert x == 20;
}
