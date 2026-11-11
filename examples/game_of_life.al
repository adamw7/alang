// Conway's Game of Life on a wrap-around board. A board is a plain value: `step` builds a new
// board and never changes its argument, so any two generations can be kept and compared.

const W: i64 = 10;
const H: i64 = 8;

struct Board {
    cells: [[bool; W]; H],
}

/// Rows of text, `#` for a live cell and anything else for a dead one; short rows are padded.
fn parse(rows: [str[W]; H]) -> Board {
    var cells: [[bool; W]; H] = [[false; W]; H];
    for y in 0..H {
        for x in 0..len(rows[y]) {
            cells[y][x] = rows[y][x] == '#';
        }
    }
    return Board(cells: cells);
}

fn render(b: Board) -> [str[W]; H] {
    var rows: [str[W]; H] = [""; H];
    for y in 0..H {
        for alive in b.cells[y] {
            if alive {
                push(mut rows[y], '#');
            } else {
                push(mut rows[y], '.');
            }
        }
    }
    return rows;
}

fn live_neighbors(b: Board, x: i64, y: i64) -> i64 {
    var count: i64 = 0;
    for dy in -1..2 {
        for dx in -1..2 {
            // Adding W (or H) first keeps the remainder non-negative, so the edges wrap around.
            let nx: i64 = (x + dx + W) % W;
            let ny: i64 = (y + dy + H) % H;
            if (dx != 0 or dy != 0) and b.cells[ny][nx] {
                count += 1;
            }
        }
    }
    return count;
}

fn step(b: Board) -> Board {
    var next: [[bool; W]; H] = [[false; W]; H];
    for y in 0..H {
        for x in 0..W {
            let n: i64 = live_neighbors(b, x, y);
            next[y][x] = n == 3 or (n == 2 and b.cells[y][x]);
        }
    }
    return Board(cells: next);
}

fn population(b: Board) -> i64 {
    var count: i64 = 0;
    for row in b.cells {
        for alive in row {
            if alive {
                count += 1;
            }
        }
    }
    return count;
}

/// Advances a 10x8 Game of Life board (rows of `#` and `.`, edges wrapping around) by up to 100 generations.
tool fn life(rows: [str[W]; H], generations: i64) -> [str[W]; H]
    requires 0 <= generations and generations <= 100
{
    var b: Board = parse(rows);
    for g in 0..generations {
        b = step(b);
    }
    return render(b);
}

const GLIDER: [str[W]; H] = [
    ".#",
    "..#",
    "###",
    "",
    "",
    "",
    "",
    "",
];

fn show(title: str[32], b: Board) {
    print(title, " (population ", population(b), ")");
    for row in render(b) {
        print(row);
    }
}

fn main() {
    var b: Board = parse(GLIDER);
    show("generation 0", b);
    for g in 0..4 {
        b = step(b);
    }
    show("generation 4", b);
}

test "a block is a still life" {
    let block: Board = parse(["", ".##", ".##", "", "", "", "", ""]);
    assert step(block) == block;
}

test "a blinker has period 2" {
    let blinker: Board = parse(["", "", "...###", "", "", "", "", ""]);
    let next: Board = step(blinker);
    assert next != blinker;
    assert next == parse(["", "....#", "....#", "....#", "", "", "", ""]);
    assert step(next) == blinker;
}

test "a glider moves one cell diagonally every four generations" {
    let start: Board = parse(GLIDER);
    let moved: Board = parse(["", "..#", "...#", ".###", "", "", "", ""]);
    var b: Board = start;
    for g in 0..4 {
        b = step(b);
        assert population(b) == 5;
    }
    assert b == moved;
}

test "stepping leaves the original board unchanged" {
    let start: Board = parse(GLIDER);
    let copy: Board = start;
    let later: Board = step(step(start));
    assert start == copy;
    assert later != start;
}

test "the tool wraps around the edges" {
    // After 40 generations the glider has moved 10 cells right (a full lap) and 10 down (2 past a lap).
    let rows: [str[W]; H] = life(GLIDER, 40);
    assert rows == ["..........", "..........", ".#........", "..#.......", "###.......", "..........", "..........", ".........."];
}
