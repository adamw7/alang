fn main() {
    var rng: u64 = 1;
    let a: i64 = random_int(mut rng, 5, 5);
    print(a);
    let b: i64 = random_int(mut rng, 6, 5);
    print(b);
}
