// Numbers and text formatted with fixed, pad_left and pad_right; floats read from text with f64(s).
fn money(cents: i64) -> str[24] {
    var s: str[24] = "$";
    append(mut s, cents / 100, ".", pad_left(cents % 100, 2, '0'));
    return s;
}

fn main() {
    print(money(1905), " ", money(5997), " ", money(7));
    print(fixed(3.14159, 2), " ", fixed(2.5, 0), " ", fixed(3.5, 0), " ", fixed(0.125, 2), " ", fixed(2.675, 2));
    print(fixed(-0.001, 2), " ", fixed(f32(0.1), 10), " ", fixed(1.0e20, 3), " ", fixed(123.456, 20));
    print(fixed(0.0 / 0.0, 2), " ", fixed(1.0 / 0.0, 2), " ", fixed(-1.0 / 0.0, 0), " ", fixed(-0.0, 0));
    print("[", pad_left("ab", 5, ' '), "] [", pad_right("ab", 5, '.'), "] [", pad_left("toolong", 3, '*'), "]");
    print("[", pad_left(fixed(19.999, 2), 8, ' '), "] [", pad_right(true, 6, '-'), "] [", pad_left(-42, 6, '0'), "]");
    let w: u8 = 4;
    print(pad_left(pad_right("x", w, '>'), 6, '<'), " ", pad_left(2.5, 7, '_'), " ", pad_right(u64(7), -1, '?'));
    let cell: str[8] = pad_left(7, 3, '0');
    var third: str[16] = "";
    third = fixed(1.0 / 3.0, 10);
    print(cell, " ", third, " ", len(cell), " ", cap(cell));

    // A table with aligned columns.
    let names: [str[8]; 3] = ["apple", "kiwi", "fig"];
    let prices: [f64; 3] = [1.5, 12.25, 0.333];
    for i in 0..3 {
        print(pad_right(names[i], 8, ' '), pad_left(fixed(prices[i], 2), 7, ' '));
    }

    // Reading floats: is_float says whether f64(s) converts the text.
    let texts: [str[32]; 8] = ["19.99", "-0.5", "3.", ".25", "1e5", "1.2.3", "12345678901234567", "+7.125"];
    var total: f64 = 0.0;
    for t in texts {
        if is_float(t) {
            total += f64(t);
        } else {
            print("not a number f64 converts: ", t);
        }
    }
    print(fixed(total, 3));

    // Strings are ordered byte by byte, a prefix first, as sort orders them.
    print("apple" < "banana", " ", "app" < "apple", " ", "Zebra" < "apple", " ", "b" >= "a", " ", "x" <= "x", " ", "" > "a");
}
