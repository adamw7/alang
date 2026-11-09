tool fn grid_path(grid: list[str[8]; 8]) -> i64 {
    let rows: i64 = len(grid);
    let cols: i64 = len(grid[0]);
    if grid[0][0] == '#' or grid[rows - 1][cols - 1] == '#' {
        return -1;
    }
    var dist: [i64; 64] = [-1; 64];
    var queue: [i64; 64] = [0; 64];
    var head: i64 = 0;
    var tail: i64 = 1;
    dist[0] = 0;
    let goal: i64 = (rows - 1) * cols + (cols - 1);
    while head < tail limit 64 {
        let cell: i64 = queue[head];
        head += 1;
        if cell == goal {
            return dist[cell];
        }
        let r: i64 = cell / cols;
        let c: i64 = cell % cols;
        for d in 0..4 {
            var nr: i64 = r;
            var nc: i64 = c;
            match d {
                0 => { nr += 1; }
                1 => { nr -= 1; }
                2 => { nc += 1; }
                _ => { nc -= 1; }
            }
            if nr >= 0 and nr < rows and nc >= 0 and nc < cols {
                let next: i64 = nr * cols + nc;
                if grid[nr][nc] == '.' and dist[next] < 0 {
                    dist[next] = dist[cell] + 1;
                    queue[tail] = next;
                    tail += 1;
                }
            }
        }
    }
    return -1;
}
