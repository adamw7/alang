// A key given twice in a map or set literal is kept once, so only the different ones count against the capacity.
// Keys that are not constants are counted as the literal is built (keyed_literal_trap.al).
const PAIR: set[str[2]; 2] = ["b", "a", "b"];

fn main() {
    let s: set[i64; 2] = [3, 1, 3];
    let m: map[str[1], i64; 2] = [("a", 1), ("b", 2), ("a", 3)];
    let z: set[f64; 3] = [0.0, -0.0, 0.0 / 0.0, 0.0 / 0.0];
    let x: i64 = 3;
    let t: set[i64; 2] = [x, 1, x];
    print(s, " ", m, " ", PAIR, " ", z, " ", t);
}
