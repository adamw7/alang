// FizzBuzz: loops, conditions and building text in a fixed-capacity str.

fn fizzbuzz(n: i64) -> str[16] {
    var out: str[16] = "";
    if n % 15 == 0 {
        append(mut out, "FizzBuzz");
    } else if n % 3 == 0 {
        append(mut out, "Fizz");
    } else if n % 5 == 0 {
        append(mut out, "Buzz");
    } else {
        append(mut out, n);
    }
    return out;
}

fn main() {
    for i in 1..16 {
        print(fizzbuzz(i));
    }
}

test "multiples of three and five" {
    assert fizzbuzz(3) == "Fizz";
    assert fizzbuzz(10) == "Buzz";
    assert fizzbuzz(30) == "FizzBuzz";
}

test "other numbers are printed as digits" {
    assert fizzbuzz(7) == "7";
    assert fizzbuzz(98) == "98";
}
