// fn main receives the standard input (tests/run/input.in): sums the numbers on its lines.
fn main(input: str[64]) {
    var lines: i64 = 0;
    var total: i64 = 0;
    for line in split(trim(input), '\n') {
        lines += 1;
        if is_int(trim(line)) {
            total += i64(trim(line));
        } else {
            print("skipped: ", line);
        }
    }
    print("lines: ", lines, ", total: ", total, ", bytes: ", len(input));
}
