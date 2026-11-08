// Breadth-first search through a maze. ALang forbids recursion, so graph
// algorithms keep their pending work in a fixed-capacity queue instead.

const W: i64 = 8;
const H: i64 = 6;
const CELLS: i64 = W * H;

const MAZE: [str[8]; 6] = [
    "S..#....",
    ".#.#.##.",
    ".#...#..",
    ".####.#.",
    "......#E",
    "#.##....",
];

struct Pos {
    x: i64,
    y: i64,
}

fn find(c: u8) -> Pos {
    for y in 0..H {
        for x in 0..W {
            if MAZE[y][x] == c {
                return Pos(x: x, y: y);
            }
        }
    }
    panic("marker not found in maze");
}

/// Length of the shortest path from S to E, or -1 if E is unreachable.
fn shortest_path() -> i64 {
    var dist: [i64; CELLS] = [-1; CELLS];
    var queue: list[Pos; CELLS] = [];
    var head: i64 = 0;
    let start: Pos = find('S');
    let goal: Pos = find('E');
    let dx: [i64; 4] = [1, -1, 0, 0];
    let dy: [i64; 4] = [0, 0, 1, -1];

    dist[start.y * W + start.x] = 0;
    push(mut queue, start);
    while head < len(queue) limit CELLS {
        let p: Pos = queue[head];
        head += 1;
        if p == goal {
            return dist[p.y * W + p.x];
        }
        for d in 0..4 {
            let nx: i64 = p.x + dx[d];
            let ny: i64 = p.y + dy[d];
            let inside: bool = nx >= 0 and nx < W and ny >= 0 and ny < H;
            if inside and MAZE[ny][nx] != '#' and dist[ny * W + nx] == -1 {
                dist[ny * W + nx] = dist[p.y * W + p.x] + 1;
                push(mut queue, Pos(x: nx, y: ny));
            }
        }
    }
    return -1;
}

fn main() {
    for row in MAZE {
        print(row);
    }
    print("shortest path: ", shortest_path(), " steps");
}

test "finds the shortest path" {
    assert shortest_path() == 13;
}
