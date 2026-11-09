const VALUES: [i64; 13] = [1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1];
const SYMBOLS: [str[2]; 13] = ["M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"];

tool fn int_to_roman(n: i64) -> str[15]
    requires n >= 1 and n <= 3999
{
    var rest: i64 = n;
    var out: str[15] = "";
    for i in 0..13 {
        while rest >= VALUES[i] limit 3 {
            append(mut out, SYMBOLS[i]);
            rest -= VALUES[i];
        }
    }
    return out;
}
