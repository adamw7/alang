// Prime numbers: a sieve over a fixed-size array, trial-division factoring, and
// contracts that state (and check) what each function promises.

const SIEVE_SIZE: i64 = 1000;
// There are exactly 168 primes below 1000, so this list can never overflow.
const MAX_PRIMES: i64 = 168;
// Trial division up to sqrt(MAX_N) = 1_000_000 keeps every call fast and every product in range.
const MAX_N: i64 = 1_000_000_000_000;
// 2^40 > MAX_N, so no number in range has 40 prime factors.
const MAX_FACTORS: i64 = 40;

/// All primes below n (2 <= n <= 1000), smallest first.
tool fn primes_below(n: i64) -> list[i64; MAX_PRIMES]
    requires 2 <= n and n <= SIEVE_SIZE
{
    var composite: [bool; SIEVE_SIZE] = [false; SIEVE_SIZE];
    var primes: list[i64; MAX_PRIMES] = [];
    for p in 2..n {
        if not composite[p] {
            push(mut primes, p);
            // Smaller multiples of p were already crossed out by smaller primes.
            var m: i64 = p * p;
            while m < n limit n {
                composite[m] = true;
                m += p;
            }
        }
    }
    return primes;
}

fn is_prime(n: i64) -> bool
    requires n <= MAX_N
{
    if n < 2 {
        return false;
    }
    var d: i64 = 2;
    while d * d <= n limit 1_000_000 {
        if n % d == 0 {
            return false;
        }
        d += 1;
    }
    return true;
}

fn product(xs: list[i64; MAX_FACTORS]) -> i64 {
    var p: i64 = 1;
    for x in xs {
        p *= x;
    }
    return p;
}

fn all_prime(xs: list[i64; MAX_FACTORS]) -> bool {
    for x in xs {
        if not is_prime(x) {
            return false;
        }
    }
    return true;
}

/// Prime factors of n (2 <= n <= 10^12) in ascending order, with repetition: 360 -> [2, 2, 2, 3, 3, 5].
tool fn factorize(n: i64) -> list[i64; MAX_FACTORS]
    requires 2 <= n and n <= MAX_N
    ensures product(result) == n
    ensures all_prime(result)
{
    var factors: list[i64; MAX_FACTORS] = [];
    var rest: i64 = n;
    var d: i64 = 2;
    // Each iteration either divides `rest` (fewer than 40 times) or moves `d` up (to at most 10^6).
    while d * d <= rest limit 1_000_040 {
        if rest % d == 0 {
            push(mut factors, d);
            rest /= d;
        } else {
            d += 1;
        }
    }
    if rest > 1 {
        push(mut factors, rest);
    }
    return factors;
}

/// Greatest common divisor of two non-negative numbers by Euclid's algorithm (the builtin gcd(a, b) does the same).
tool fn euclid(a: i64, b: i64) -> i64
    requires a >= 0 and b >= 0
{
    var x: i64 = a;
    var y: i64 = b;
    // Consecutive Fibonacci numbers are the slowest case, and F(93) no longer fits an i64.
    while y != 0 limit 93 {
        let r: i64 = x % y;
        x = y;
        y = r;
    }
    return x;
}

fn main() {
    print("primes below 50: ", primes_below(50));
    let numbers: [i64; 4] = [360, 1001, 65537, 600851475143];
    for n in numbers {
        print(n, " = ", factorize(n));
    }
    print("gcd(1071, 462) = ", euclid(1071, 462));
}

test "there are 25 primes below 100 and 168 below 1000" {
    assert len(primes_below(100)) == 25;
    let all: list[i64; MAX_PRIMES] = primes_below(SIEVE_SIZE);
    assert len(all) == 168;
    assert all[167] == 997;
}

test "the sieve agrees with trial division" {
    let primes: list[i64; MAX_PRIMES] = primes_below(200);
    var next: i64 = 0;
    for n in 2..200 {
        if is_prime(n) {
            assert primes[next] == n;
            next += 1;
        }
    }
    assert next == len(primes);
}

test "factorization" {
    assert factorize(360) == [2, 2, 2, 3, 3, 5];
    assert factorize(97) == [97];
    assert factorize(1_000_003) == [1_000_003];
    assert len(factorize(1 << 39)) == 39;
    assert factorize(MAX_N) == [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5];
}

test "gcd" {
    assert gcd(1071, 462) == 21;
    assert gcd(0, 5) == 5;
    assert gcd(17, 0) == 17;
    // F(92) and F(91), the slowest pair that fits.
    assert gcd(7_540_113_804_746_346_429, 4_660_046_610_375_530_309) == 1;
}

test "euclid agrees with the builtin gcd" {
    for a in 0..40 {
        for b in 0..40 {
            assert euclid(a, b) == gcd(a, b);
        }
    }
    assert euclid(1071, 462) == 21;
}
