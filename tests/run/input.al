// fn main receives the standard input (tests/run/input.in): sums the numbers on its lines.
fn main(input: str[64]) {
    let lines: list[str[16]; 8] = split(trim(input), '\n');
    var total: i64 = 0;
    for line in lines {
        if is_int(trim(line)) {
            total += i64(trim(line));
        } else {
            print("skipped: ", line);
        }
    }
    print("lines: ", len(lines), ", total: ", total, ", bytes: ", len(input));
}
