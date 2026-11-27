// abs_diff, sign, pow_mod, the statistics of a list of numbers, and the seeded random numbers.
fn main() {
    print(abs_diff(3, 10), " ", abs_diff(u8(3), 5), " ", abs_diff(i64.MIN, -1), " ", abs_diff(i64.MAX, 0), " ", abs_diff(2.5, -1.0));
    print(abs_diff(u64.MAX, 0), " ", abs_diff(i8(-128), i8(-1)), " ", abs_diff(f32(1.5), f32(2.0)), " ", abs_diff(-5, -5));
    print(sign(-7), " ", sign(0), " ", sign(42), " ", sign(u8(0)), " ", sign(u8(9)), " ", sign(-2.5), " ", sign(0.0), " ", sign(-0.0));
    print(sign(f64.INFINITY), " ", is_nan(sign(f64.NAN)), " ", sign(i64.MIN), " ", sign(f32(3.0)));
    print(pow_mod(2, 10, 1000), " ", pow_mod(3, 0, 7), " ", pow_mod(5, 0, 1), " ", pow_mod(-2, 3, 5), " ", pow_mod(0, 0, 9));
    print(pow_mod(2, 1000000007 - 2, 1000000007), " ", pow_mod(u64.MAX, u64.MAX, u64.MAX - 58), " ", pow_mod(i64.MAX, i64.MAX, 1000003));
    let xs: list[i64; 8] = [4, 1, 3, 2];
    print(mean(xs), " ", median(xs), " ", variance(xs), " ", stddev(xs), " ", xs);
    let fs: [f64; 5] = [2.5, -1.0, 7.25, 0.5, 3.0];
    print(mean(fs), " ", median(fs), " ", fixed(variance(fs), 6), " ", fixed(stddev(fs), 6));
    let us: list[u64; 4] = [u64.MAX, 1];
    print(mean(us), " ", median([7]), " ", median([u8(3), 1, 2]), " ", variance([5, 5, 5]), " ", mean([f32(0.1), f32(0.2)]));
    var rng: u64 = 42;
    let r1: u64 = random_u64(mut rng);
    let r2: u64 = random_u64(mut rng);
    print(r1, " ", r2, " ", rng);
    var dice: list[i64; 16] = [];
    for _ in 0..16 {
        let d: i64 = random_int(mut rng, 1, 6);
        push(mut dice, d);
    }
    print(dice);
    var seed: i64 = -1;
    let x: i64 = random_int(mut seed, i64.MIN, i64.MAX);
    let y: i64 = random_int(mut seed, -3, -3);
    let f: f64 = random_f64(mut seed);
    print(x, " ", y, " ", fixed(f, 6), " ", f >= 0.0 and f < 1.0, " ", seed);
    var deck: [i64; 10] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
    var s: u64 = 7;
    shuffle(mut deck, mut s);
    print(deck);
    var word: str[8] = "abcdefg";
    shuffle(mut word, mut s);
    var names: list[str[4]; 4] = ["ann", "bob", "cy"];
    shuffle(mut names, mut s);
    var none: list[i64; 2] = [];
    shuffle(mut none, mut s);
    print(word, " ", names, " ", none, " ", s);
    var total: i64 = 0;
    for _ in 0..1000 {
        let r: i64 = random_int(mut rng, 0, 2);
        total += r;
    }
    print(total);
}

test "the same seed gives the same numbers" {
    var a: u64 = 5;
    var b: u64 = 5;
    let x: u64 = random_u64(mut a);
    let y: u64 = random_u64(mut b);
    assert x == y;
    assert a == b;
}

test "an empty list has no mean" traps R0013 {
    let xs: list[f64; 4] = [];
    print(mean(xs));
}
