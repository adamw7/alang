// A Sudoku solver. Backtracking is usually written with recursion, which ALang forbids:
// here the guesses live in an explicit list used as a stack, and the digits already used in
// each row, column and box are bit sets in a u16 (bit d set: digit d is taken).

const SIZE: i64 = 9;
const CELLS: i64 = 81;
// Bits 1 to 9: a row, column or box that holds every digit once.
const ALL_DIGITS: u16 = 0x3FE;
// Plenty for puzzles made for people; past it the solver answers Solution.GaveUp.
const MAX_STEPS: i64 = 1_000_000;

enum Solution {
    Solved(str[81]),
    NoSolution,
    GaveUp,
    WrongLength(i64),
    BadChar(i64),
    Conflict(i64),
}

struct Board {
    digits: [i64; CELLS],
    rows: [u16; SIZE],
    cols: [u16; SIZE],
    boxes: [u16; SIZE],
}

fn bit(digit: i64) -> u16 {
    let one: u16 = 1;
    return one << digit;
}

fn box_of(cell: i64) -> i64 {
    return (cell / SIZE / 3) * 3 + (cell % SIZE) / 3;
}

fn is_free(b: Board, cell: i64, digit: i64) -> bool {
    let used: u16 = b.rows[cell / SIZE] | b.cols[cell % SIZE] | b.boxes[box_of(cell)];
    return (used & bit(digit)) == 0;
}

fn place(b: mut Board, cell: i64, digit: i64) {
    let m: u16 = bit(digit);
    let k: i64 = box_of(cell);
    b.digits[cell] = digit;
    b.rows[cell / SIZE] = b.rows[cell / SIZE] | m;
    b.cols[cell % SIZE] = b.cols[cell % SIZE] | m;
    b.boxes[k] = b.boxes[k] | m;
}

fn unplace(b: mut Board, cell: i64) {
    let keep: u16 = ~bit(b.digits[cell]);
    let k: i64 = box_of(cell);
    b.digits[cell] = 0;
    b.rows[cell / SIZE] = b.rows[cell / SIZE] & keep;
    b.cols[cell % SIZE] = b.cols[cell % SIZE] & keep;
    b.boxes[k] = b.boxes[k] & keep;
}

/// False only for a solved grid that breaks a rule or changes a given of `puzzle`.
fn fits(puzzle: str[81], s: Solution) -> bool {
    match s {
        Solution.Solved(grid) => {
            var rows: [u16; SIZE] = [0; SIZE];
            var cols: [u16; SIZE] = [0; SIZE];
            var boxes: [u16; SIZE] = [0; SIZE];
            for i in 0..CELLS {
                let c: u8 = grid[i];
                if c < '1' or c > '9' {
                    return false;
                }
                if puzzle[i] != '.' and puzzle[i] != '0' and puzzle[i] != c {
                    return false;
                }
                let m: u16 = bit(i64(c - '0'));
                rows[i / SIZE] = rows[i / SIZE] | m;
                cols[i % SIZE] = cols[i % SIZE] | m;
                boxes[box_of(i)] = boxes[box_of(i)] | m;
            }
            for k in 0..SIZE {
                if rows[k] != ALL_DIGITS or cols[k] != ALL_DIGITS or boxes[k] != ALL_DIGITS {
                    return false;
                }
            }
            return true;
        }
        _ => {
            return true;
        }
    }
}

/// Solves a Sudoku written as 81 characters, row by row: 1-9 for a given digit, `.` or `0` for an
/// empty cell. A solution comes back in the same format; offsets in errors count from 0.
tool fn solve(puzzle: str[81]) -> Solution
    ensures fits(puzzle, result)
{
    if len(puzzle) != CELLS {
        return Solution.WrongLength(len(puzzle));
    }
    var b: Board = Board(digits: [0; CELLS], rows: [0; SIZE], cols: [0; SIZE], boxes: [0; SIZE]);
    var empty: list[i64; CELLS] = [];
    for i in 0..CELLS {
        let c: u8 = puzzle[i];
        if c == '.' or c == '0' {
            push(mut empty, i);
        } else if c >= '1' and c <= '9' {
            let digit: i64 = i64(c - '0');
            if not is_free(b, i, digit) {
                return Solution.Conflict(i);
            }
            place(mut b, i, digit);
        } else {
            return Solution.BadChar(i);
        }
    }

    // The cells empty[0..k] hold guesses that fit so far. Each step tries the next digit for
    // empty[k]: if one fits, move on to k + 1; if none is left, clear the cell and go back to k - 1.
    var k: i64 = 0;
    var steps: i64 = 0;
    while k < len(empty) and steps < MAX_STEPS limit MAX_STEPS {
        steps += 1;
        let cell: i64 = empty[k];
        let previous: i64 = b.digits[cell];
        if previous != 0 {
            unplace(mut b, cell);
        }
        var next: i64 = 0;
        for digit in previous + 1..10 {
            if is_free(b, cell, digit) {
                next = digit;
                break;
            }
        }
        if next != 0 {
            place(mut b, cell, next);
            k += 1;
        } else if k == 0 {
            return Solution.NoSolution;
        } else {
            k -= 1;
        }
    }
    if k < len(empty) {
        return Solution.GaveUp;
    }

    var out: str[81] = "";
    for d in b.digits {
        push(mut out, u8(d) + '0');
    }
    return Solution.Solved(out);
}

fn print_grid(grid: str[81]) {
    for r in 0..SIZE {
        var line: str[32] = "";
        for c in 0..SIZE {
            if c == 3 or c == 6 {
                append(mut line, " |");
            }
            if c > 0 {
                push(mut line, ' ');
            }
            push(mut line, grid[r * SIZE + c]);
        }
        print(line);
        if r == 2 or r == 5 {
            print("------+-------+------");
        }
    }
}

const PUZZLE: str[81] = "53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79";

fn main() {
    print_grid(PUZZLE);
    print("");
    match solve(PUZZLE) {
        Solution.Solved(grid) => {
            print_grid(grid);
        }
        _ => {
            print(solve(PUZZLE));
        }
    }
}

test "solves the puzzle" {
    assert solve(PUZZLE) == Solution.Solved("534678912672195348198342567859761423426853791713924856961537284287419635345286179");
}

test "a solved grid is returned as it is" {
    let grid: str[81] = "534678912672195348198342567859761423426853791713924856961537284287419635345286179";
    assert solve(grid) == Solution.Solved(grid);
}

test "fills an empty grid" {
    var empty: str[81] = "";
    for i in 0..CELLS {
        push(mut empty, '.');
    }
    match solve(empty) {
        Solution.Solved(grid) => {
            assert grid[0] == '1';
            assert fits(empty, Solution.Solved(grid));
        }
        _ => {
            panic("expected a solution");
        }
    }
}

test "reports bad input as values" {
    assert solve("123") == Solution.WrongLength(3);
    // Two 5s in the first row.
    assert solve("55..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79") == Solution.Conflict(1);
    assert solve("53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..7x") == Solution.BadChar(80);
}

test "detects a puzzle without a solution" {
    // Every given is consistent, but the first cell cannot hold any digit: 1-8 are in its row, 9 in its column.
    let puzzle: str[81] = ".123456789.......................................................................";
    assert solve(puzzle) == Solution.NoSolution;
}
