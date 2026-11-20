package alang.ast;

/**
 * The builtin functions: the core ones and the standard library. Their names are reserved and cannot be
 * declared by programs. They are polymorphic in ways user functions are not (beyond type parameters):
 * {@code len} takes any array, list or str, {@code sort} any list, and so on.
 */
public enum Builtin {
    // ---- core
    PRINT(Group.CORE, false, "print", "print(values...)", "Prints the values (no separator) followed by a newline."),
    LEN(Group.CORE, true, "len", "len(x) -> i64", "Number of elements in an array/list, or bytes in a str."),
    CAP(Group.CORE, true, "cap", "cap(x) -> i64", "Capacity of a list or str (the N in list[T; N] / str[N])."),
    PUSH(Group.CORE, false, "push", "push(mut xs, value)", "Appends one element to a list (or one u8 byte to a str). Traps if full."),
    POP(Group.CORE, false, "pop", "pop(mut xs) -> T", "Removes and returns the last element of a list (or last byte of a str). Traps if empty."),
    CLEAR(Group.CORE, false, "clear", "clear(mut xs)", "Removes all elements of a list or str."),
    APPEND(Group.CORE, false, "append", "append(mut s, values...)", "Appends the text form of each value to a str (same format as print). Traps if full."),
    PANIC(Group.CORE, false, "panic", "panic(message)", "Stops the program with an error message. Never returns."),
    ASSERT_EQ(Group.CORE, false, "assert_eq", "assert_eq(a, b)",
        "A statement: the same as assert a == b; for two values of a type that == compares. When they differ it traps "
            + "(R0005) and shows both: assertion failed: assert_eq(total, 10) (left: 9, right: 10)."),
    UNWRAP_OR(Group.CORE, true, "unwrap_or", "unwrap_or(o, default) -> T",
        "The value in an Option<T> (Option.Some(v)) or a Result<T, E> (Result.Ok(v)), or default, a T, when it is "
            + "Option.None or Result.Err: unwrap_or(parse_i64(s), 0)."),
    IS_SOME(Group.CORE, true, "is_some", "is_some(o) -> bool", "Whether an Option is Option.Some."),
    IS_NONE(Group.CORE, true, "is_none", "is_none(o) -> bool", "Whether an Option is Option.None."),
    IS_OK(Group.CORE, true, "is_ok", "is_ok(r) -> bool", "Whether a Result is Result.Ok."),
    IS_ERR(Group.CORE, true, "is_err", "is_err(r) -> bool", "Whether a Result is Result.Err."),

    // ---- math
    ABS(Group.MATH, true, "abs", "abs(x) -> T", "Absolute value of a signed integer or float. Traps on overflow (abs of the minimum integer)."),
    MIN(Group.MATH, true, "min", "min(a, b) -> T",
        "Smaller of two numbers of the same type. min(xs), with one array or list of numbers, is its smallest element; "
            + "traps (R0013) if it is empty."),
    MAX(Group.MATH, true, "max", "max(a, b) -> T",
        "Larger of two numbers of the same type. max(xs), with one array or list of numbers, is its largest element; "
            + "traps (R0013) if it is empty."),
    SUM(Group.MATH, true, "sum", "sum(xs) -> T",
        "The total of an array or list of numbers, added left to right; 0 if it is empty. Traps (R0001) if an integer "
            + "total does not fit its type, as + does."),
    CLAMP(Group.MATH, true, "clamp", "clamp(x, lo, hi) -> T",
        "x limited to lo..hi (numbers of the same type). Traps (R0013) unless lo <= hi."),
    POW(Group.MATH, true, "pow", "pow(x, n) -> T",
        "x to the power n. For integers: n >= 0, pow(0, 0) is 1, traps on overflow, and (R0013) if n < 0. For an f32/f64 x, "
            + "n is a float of the same type: pow(2.0, 0.5); never traps (pow(-8.0, 0.5) is NaN), with the special cases of C99."),
    GCD(Group.MATH, true, "gcd", "gcd(a, b) -> T",
        "Greatest common divisor of two integers of the same type, never negative; gcd(0, 0) is 0. Traps if it does not fit."),
    LCM(Group.MATH, true, "lcm", "lcm(a, b) -> T",
        "Least common multiple of two integers of the same type, never negative; 0 if either is 0. Traps on overflow."),
    WRAPPING_ADD(Group.MATH, true, "wrapping_add", "wrapping_add(a, b) -> T",
        "a + b for two integers of the same type, wrapped around to fit (modulo 2^bits): wrapping_add(u8(250), 10) is 4. "
            + "Never traps; for hashes and checksums."),
    WRAPPING_SUB(Group.MATH, true, "wrapping_sub", "wrapping_sub(a, b) -> T",
        "a - b for two integers of the same type, wrapped around to fit: wrapping_sub(u8(0), 1) is 255. Never traps."),
    WRAPPING_MUL(Group.MATH, true, "wrapping_mul", "wrapping_mul(a, b) -> T",
        "a * b for two integers of the same type, wrapped around to fit: the low bits of the product, as FNV and other "
            + "hashes need. Never traps."),
    SATURATING_ADD(Group.MATH, true, "saturating_add", "saturating_add(a, b) -> T",
        "a + b for two integers of the same type, or the type's largest or smallest value if it does not fit: "
            + "saturating_add(u8(250), 10) is 255. Never traps."),
    SATURATING_SUB(Group.MATH, true, "saturating_sub", "saturating_sub(a, b) -> T",
        "a - b for two integers of the same type, held to the type's range: saturating_sub(u8(3), 5) is 0. Never traps."),
    SATURATING_MUL(Group.MATH, true, "saturating_mul", "saturating_mul(a, b) -> T",
        "a * b for two integers of the same type, held to the type's range. Never traps."),
    CHECKED_ADD(Group.MATH, true, "checked_add", "checked_add(a, b) -> Option<T>",
        "a + b for two integers of the same type as Option.Some, or Option.None if it does not fit. Never traps."),
    CHECKED_SUB(Group.MATH, true, "checked_sub", "checked_sub(a, b) -> Option<T>",
        "a - b for two integers of the same type as Option.Some, or Option.None if it does not fit: "
            + "checked_sub(u8(3), 5) is Option.None. Never traps."),
    CHECKED_MUL(Group.MATH, true, "checked_mul", "checked_mul(a, b) -> Option<T>",
        "a * b for two integers of the same type as Option.Some, or Option.None if it does not fit. Never traps."),
    REM_EUCLID(Group.MATH, true, "rem_euclid", "rem_euclid(a, m) -> T",
        "The remainder of a / m that is never negative, for two integers of the same type: 0 <= r < abs(m). "
            + "rem_euclid(-1, 5) is 4, where -1 % 5 is -1; use it to wrap an index around: xs[rem_euclid(i + k, n)]. "
            + "Traps (R0002) if m is 0."),
    DIV_EUCLID(Group.MATH, true, "div_euclid", "div_euclid(a, m) -> T",
        "The quotient that goes with rem_euclid: a == div_euclid(a, m) * m + rem_euclid(a, m). div_euclid(-7, 2) is -4, "
            + "where -7 / 2 is -3. Traps (R0002) if m is 0, and (R0001) where / overflows."),
    MUL_MOD(Group.MATH, true, "mul_mod", "mul_mod(a, b, m) -> T",
        "(a * b) mod m, computed exactly however large a * b is, for three integers of the same type: "
            + "rem_euclid(a, m) * rem_euclid(b, m) reduced mod m, so 0 <= result < m. Traps (R0013) unless m > 0."),
    COUNT_ONES(Group.MATH, true, "count_ones", "count_ones(x) -> i64",
        "The number of 1 bits in the integer x, over the bits of its type (two's complement for a signed one): "
            + "count_ones(u8(7)) is 3, count_ones(i8(-1)) is 8."),
    LEADING_ZEROS(Group.MATH, true, "leading_zeros", "leading_zeros(x) -> i64",
        "The number of 0 bits above the highest 1 bit of the integer x, over the bits of its type: leading_zeros(u8(1)) "
            + "is 7; the number of bits if x is 0."),
    TRAILING_ZEROS(Group.MATH, true, "trailing_zeros", "trailing_zeros(x) -> i64",
        "The number of 0 bits below the lowest 1 bit of the integer x: trailing_zeros(u8(8)) is 3; the number of bits "
            + "of its type if x is 0."),
    ROTL(Group.MATH, true, "rotl", "rotl(x, n) -> T",
        "The bits of the integer x shifted left by n (any integer), those that leave at the top coming back at the "
            + "bottom: rotl(u8(0x81), 1) is 0x03. n is taken modulo the number of bits, rem_euclid(n, bits), so a "
            + "negative n rotates right. Never traps."),
    ROTR(Group.MATH, true, "rotr", "rotr(x, n) -> T",
        "The bits of the integer x shifted right by n, those that leave at the bottom coming back at the top: "
            + "rotr(u8(0x81), 1) is 0xc0. n is taken modulo the number of bits. Never traps."),
    SQRT(Group.MATH, true, "sqrt", "sqrt(x) -> f", "Square root of an f32/f64."),
    FLOOR(Group.MATH, true, "floor", "floor(x) -> f", "Largest integral value not greater than x (f32/f64)."),
    CEIL(Group.MATH, true, "ceil", "ceil(x) -> f", "Smallest integral value not less than x (f32/f64)."),
    ROUND(Group.MATH, true, "round", "round(x) -> f", "Nearest integral value, halves away from zero: round(2.5) is 3.0 (f32/f64)."),
    TRUNC(Group.MATH, true, "trunc", "trunc(x) -> f", "x without its fraction, rounded toward zero: trunc(-2.7) is -2.0 (f32/f64)."),
    EXP(Group.MATH, true, "exp", "exp(x) -> f", "e to the power x (f32/f64)."),
    LOG(Group.MATH, true, "log", "log(x) -> f", "Natural logarithm of x (f32/f64): -inf for 0, NaN below 0."),
    LOG2(Group.MATH, true, "log2", "log2(x) -> f", "Base-2 logarithm of x (f32/f64); exact for powers of two."),
    LOG10(Group.MATH, true, "log10", "log10(x) -> f", "Base-10 logarithm of x (f32/f64)."),
    SIN(Group.MATH, true, "sin", "sin(x) -> f", "Sine of x radians (f32/f64)."),
    COS(Group.MATH, true, "cos", "cos(x) -> f", "Cosine of x radians (f32/f64)."),
    TAN(Group.MATH, true, "tan", "tan(x) -> f", "Tangent of x radians (f32/f64)."),
    ASIN(Group.MATH, true, "asin", "asin(x) -> f", "Arc sine, in radians from -pi/2 to pi/2 (f32/f64); NaN outside -1..1."),
    ACOS(Group.MATH, true, "acos", "acos(x) -> f", "Arc cosine, in radians from 0 to pi (f32/f64); NaN outside -1..1."),
    ATAN(Group.MATH, true, "atan", "atan(x) -> f", "Arc tangent, in radians from -pi/2 to pi/2 (f32/f64)."),
    ATAN2(Group.MATH, true, "atan2", "atan2(y, x) -> f",
        "The angle of the point (x, y) from the positive x axis, in radians from -pi to pi (two floats of the same type)."),
    IS_NAN(Group.MATH, true, "is_nan", "is_nan(x) -> bool", "Whether an f32/f64 is NaN."),
    IS_FINITE(Group.MATH, true, "is_finite", "is_finite(x) -> bool", "Whether an f32/f64 is neither NaN nor infinite."),

    // ---- characters: ASCII, on one u8 byte
    IS_DIGIT(Group.CHARS, true, "is_digit", "is_digit(c) -> bool", "Whether the u8 byte c is an ASCII digit 0-9."),
    IS_ALPHA(Group.CHARS, true, "is_alpha", "is_alpha(c) -> bool", "Whether the u8 byte c is an ASCII letter a-z or A-Z."),
    IS_ALNUM(Group.CHARS, true, "is_alnum", "is_alnum(c) -> bool", "Whether the u8 byte c is an ASCII letter or digit."),
    IS_SPACE(Group.CHARS, true, "is_space", "is_space(c) -> bool",
        "Whether the u8 byte c is ASCII whitespace: space, \\t, \\n, \\r, \\x0b or \\x0c."),
    IS_UPPER(Group.CHARS, true, "is_upper", "is_upper(c) -> bool", "Whether the u8 byte c is an ASCII uppercase letter A-Z."),
    IS_LOWER(Group.CHARS, true, "is_lower", "is_lower(c) -> bool", "Whether the u8 byte c is an ASCII lowercase letter a-z."),
    TO_UPPER(Group.CHARS, true, "to_upper", "to_upper(x) -> T",
        "A u8 byte or a str[N] with ASCII a-z changed to A-Z; other bytes are kept."),
    TO_LOWER(Group.CHARS, true, "to_lower", "to_lower(x) -> T",
        "A u8 byte or a str[N] with ASCII A-Z changed to a-z; other bytes are kept."),

    // ---- text
    CONTAINS(Group.TEXT, true, "contains", "contains(xs, x) -> bool",
        "Whether a str contains a str or u8 byte, or an array/list contains an element equal to x."),
    INDEX_OF(Group.TEXT, true, "index_of", "index_of(xs, x) -> i64",
        "Position of the first occurrence of x (as for contains), or -1 if there is none."),
    LAST_INDEX_OF(Group.TEXT, true, "last_index_of", "last_index_of(xs, x) -> i64",
        "Position of the last occurrence of x (as for contains), or -1 if there is none; for the empty str, len(xs)."),
    STARTS_WITH(Group.TEXT, true, "starts_with", "starts_with(s, prefix) -> bool", "Whether a str begins with a str or u8 byte."),
    ENDS_WITH(Group.TEXT, true, "ends_with", "ends_with(s, suffix) -> bool", "Whether a str ends with a str or u8 byte."),
    SLICE(Group.TEXT, true, "slice", "slice(xs, from, to) -> T",
        "A copy of the elements from..to (half-open) of a str, list or array, of the same type (a list for an array). "
            + "Traps (R0003) unless 0 <= from <= to <= len(xs)."),
    TRIM(Group.TEXT, true, "trim", "trim(s) -> str[N]", "s without the ASCII whitespace at its start and end."),
    STRIP_PREFIX(Group.TEXT, true, "strip_prefix", "strip_prefix(s, prefix) -> str[N]",
        "s without prefix (a str or u8 byte) at its start, or s unchanged if it does not start with it, as a str of the "
            + "capacity of s: strip_prefix(\"v1.2\", 'v') is \"1.2\". starts_with(s, prefix) tells which."),
    STRIP_SUFFIX(Group.TEXT, true, "strip_suffix", "strip_suffix(s, suffix) -> str[N]",
        "s without suffix (a str or u8 byte) at its end, or s unchanged if it does not end with it: "
            + "strip_suffix(\"a.txt\", \".txt\") is \"a\"."),
    SPLIT_ONCE(Group.TEXT, true, "split_once", "split_once(s, sep) -> Option<(str[N], str[N])>",
        "The text before and after the first occurrence of sep (a non-empty str or a u8 byte) in s, each a str of the "
            + "capacity of s (or of the wider strs of the Option it is stored as), or Option.None if s has no sep: split_once(\"k=v=w\", '=') is Option.Some((\"k\", \"v=w\")). "
            + "Traps (R0013) if sep is empty."),
    REPEAT(Group.TEXT, true, "repeat", "repeat(s, n) -> str[N]",
        "The str or u8 byte s, n times over: repeat(\"ab\", 3) is \"ababab\", repeat('-', 10) a rule of dashes. Its type is "
            + "that of the variable it is stored in. Traps (R0013) if n < 0, and (R0004) if the result does not fit."),
    SPLIT(Group.TEXT, true, "split", "split(s, sep) -> list[str[K]; M]",
        "The pieces of s between occurrences of sep (a non-empty str or a u8 byte), empty pieces included. "
            + "Its type is that of the variable it is stored in. Traps if a piece or the count does not fit; "
            + "for p in split(s, sep) goes through the pieces one at a time instead, each a str of the capacity of s, "
            + "and never traps on their size or number."),
    WORDS(Group.TEXT, true, "words", "words(s) -> list[str[K]; M]",
        "The pieces of s between runs of ASCII whitespace, never empty. Its type is that of the variable it is "
            + "stored in. Traps if a word or the count does not fit; for w in words(s) goes through the words "
            + "one at a time instead, and never traps on their size or number."),
    TRY_SPLIT(Group.TEXT, false, "try_split", "try_split(mut parts, s, sep) -> bool",
        "split(s, sep) into the list parts, replacing what it held: true if every piece fits, otherwise false with parts "
            + "left empty, never a trap. For text that may not fit, such as input: var parts: list[str[16]; 4] = []; "
            + "let ok: bool = try_split(mut parts, line, ',');"),
    TRY_WORDS(Group.TEXT, false, "try_words", "try_words(mut parts, s) -> bool",
        "words(s) into the list parts, replacing what it held: true if every word fits, otherwise false with parts left "
            + "empty, never a trap."),
    JOIN(Group.TEXT, true, "join", "join(parts, sep) -> str[N]",
        "The strs of an array or list with sep (a str or u8 byte) between them. Its type is that of the variable "
            + "it is stored in. Traps if the result does not fit."),
    REPLACE(Group.TEXT, true, "replace", "replace(s, from, to) -> str[N]",
        "s with every occurrence of from (a non-empty str or a u8 byte) replaced by to, left to right. Its type is "
            + "that of the variable it is stored in. Traps if the result does not fit."),
    FIXED(Group.TEXT, true, "fixed", "fixed(x, decimals) -> str[N]",
        "The text of the f32/f64 x with exactly `decimals` digits after the point, 0 to 20 (traps (R0013) otherwise), "
            + "rounded half to even on its exact value as print rounds: fixed(3.14159, 2) is \"3.14\", fixed(2.5, 0) is "
            + "\"2\", fixed(0.125, 2) is \"0.12\". " + Builtin.TEXT_USE),
    PAD_LEFT(Group.TEXT, true, "pad_left", "pad_left(value, width, fill) -> str[N]",
        "The text of a number, bool, str or fixed(...) as print writes it, with the u8 byte fill added in front until it "
            + "is width bytes long: pad_left(7, 3, '0') is \"007\", pad_left(\"ab\", 4, ' ') is \"  ab\". Longer text is "
            + "kept whole; a width above 1048576 traps (R0013). " + Builtin.TEXT_USE),
    PAD_RIGHT(Group.TEXT, true, "pad_right", "pad_right(value, width, fill) -> str[N]",
        "As pad_left, with the fill added after the text: pad_right(\"ab\", 4, '.') is \"ab..\". " + Builtin.TEXT_USE),
    COUNT(Group.TEXT, true, "count", "count(xs, x) -> i64",
        "How often x occurs: in a str, the non-overlapping occurrences of a str or u8 byte, left to right "
            + "(traps (R0013) if the str is empty); in an array or list, the elements equal to x."),
    IS_INT(Group.TEXT, true, "is_int", "is_int(s) -> bool",
        "Whether a str is a decimal integer that fits in i64: an optional + or -, then digits. Then i64(s) converts it."),
    IS_FLOAT(Group.TEXT, true, "is_float", "is_float(s) -> bool",
        "Whether a str is a decimal number that f64(s) converts exactly to the nearest f64: an optional + or -, then "
            + "digits with at most one . among them (12, -0.5, 3.), and at most 2^53 once the zeros at either end are "
            + "left out (15 significant digits always are), between 1e-22 and about 1e37 in size, or 0. An exponent may follow: "
            + "1e5, 2.5E-3."),

    PARSE_I64(Group.TEXT, true, "parse_i64", "parse_i64(s, base = 10) -> Option<i64>",
        "The str as an i64, Option.Some(n), when is_int(s) holds (an optional + or -, then digits, within the i64 range); "
            + "otherwise Option.None. Strict: no spaces, so trim(s) first. Never traps, unlike i64(s). With a base, 2 to 36 "
            + "(traps (R0013) otherwise), the digits are 0-9 then a-z or A-Z, without a prefix: parse_i64(\"ff\", 16) is "
            + "Option.Some(255), parse_i64(\"-101\", 2) is Option.Some(-5)."),
    TO_BASE(Group.TEXT, true, "to_base", "to_base(n, base) -> str[N]",
        "The text of the integer n in the base 2 to 36 (traps (R0013) otherwise), with the digits 0-9 then a-z, no "
            + "prefix and a - before a negative n: to_base(255, 16) is \"ff\", to_base(5, 2) is \"101\", to_base(-255, 16) "
            + "is \"-ff\". pad_left(to_base(n, 16), 2, '0') gives two hex digits. " + Builtin.TEXT_USE),
    PARSE_F64(Group.TEXT, true, "parse_f64", "parse_f64(s) -> Option<f64>",
        "The str as an f64, Option.Some(x), when is_float(s) holds (19.99, -0.5, 3, .25, 2.5e3); otherwise Option.None. "
            + "Strict: no spaces, so trim(s) first. Never traps, unlike f64(s)."),

    HASH(Group.TEXT, true, "hash", "hash(s) -> u64",
        "The 64-bit FNV-1a hash of the bytes of a str, or of an array or list of u8: the same number on every run and "
            + "machine. hash(\"\") is 14695981039346656037. Not for secrets: it is easy to find two inputs with the same hash."),
    IS_UTF8(Group.TEXT, true, "is_utf8", "is_utf8(s) -> bool",
        "Whether the bytes of a str are well-formed UTF-8: no overlong forms, surrogates, code points past U+10FFFF or "
            + "cut-off sequences."),
    CHAR_COUNT(Group.TEXT, true, "char_count", "char_count(s) -> i64",
        "The number of Unicode code points in a str (len(s) counts bytes). Each byte that does not start a well-formed "
            + "UTF-8 sequence counts as one."),
    CHARS(Group.TEXT, true, "chars", "chars(s) -> list[u32; N]",
        "The Unicode code points of a str, decoding UTF-8; a byte that does not start a well-formed sequence gives "
            + "U+FFFD (65533). Its type is that of the variable it is stored in; traps (R0004) if they do not fit. "
            + "for c in chars(s) goes through them one at a time instead, each a u32, and never traps."),
    PUSH_CHAR(Group.TEXT, false, "push_char", "push_char(mut s, c)",
        "Appends the UTF-8 encoding (1 to 4 bytes) of the code point c, an integer, to a str. Traps (R0013) unless c is "
            + "0 to 0x10FFFF and not a surrogate, and (R0004) if it does not fit."),
    SLICE_CHARS(Group.TEXT, true, "slice_chars", "slice_chars(s, from, to) -> str[N]",
        "The code points from..to (half-open, counted as char_count counts them) of a str, as a str of the same capacity. "
            + "Traps (R0003) unless 0 <= from <= to <= char_count(s)."),

    // ---- collections
    SORT(Group.COLLECTIONS, false, "sort", "sort(mut xs)",
        "Sorts a list, array or str in place, ascending. Every type is ordered: numbers by value (-0.0 before 0.0, "
            + "NaN last), false before true, strs and lists element by element (a prefix first), structs field by field, "
            + "enums by variant, then payload."),
    REVERSE(Group.COLLECTIONS, false, "reverse", "reverse(mut xs)", "Reverses a list, array or str in place."),
    DEDUP(Group.COLLECTIONS, false, "dedup", "dedup(mut xs)",
        "Removes each element of a list (byte of a str) equal to the one before it, keeping the first of each run. "
            + "After sort(mut xs), every value is left once."),
    SORT_BY(Group.COLLECTIONS, false, "sort_by", "sort_by(mut xs, before)",
        "Sorts a list or array in place into the order that before, a function fn(T, T) -> bool passed by name, gives: "
            + "before(a, b) is true when a must come before b. Stable: elements neither comes before keep their order, so "
            + "sorting by one key and then by another sorts by both. A binary insertion sort: about n log2(n) calls of before."),
    ANY(Group.COLLECTIONS, false, "any", "any(xs, f) -> bool",
        "Whether f(x) is true for some element x of an array or list, f being a function fn(T) -> bool passed by name: "
            + "any(xs, is_even). Calls f from the first element on, and stops at the first true; false if xs is empty."),
    ALL(Group.COLLECTIONS, false, "all", "all(xs, f) -> bool",
        "Whether f(x) is true for every element x of an array or list (as for any); stops at the first false; true if "
            + "xs is empty."),
    COUNT_IF(Group.COLLECTIONS, false, "count_if", "count_if(xs, f) -> i64",
        "How many elements x of an array or list have f(x) true, f being a function fn(T) -> bool passed by name."),
    FIND_INDEX(Group.COLLECTIONS, false, "find_index", "find_index(xs, f) -> i64",
        "The position of the first element x of an array or list with f(x) true (as for any), or -1 if there is none."),
    RETAIN(Group.COLLECTIONS, false, "retain", "retain(mut xs, keep)",
        "Keeps the elements x of a list for which keep(x) is true, in their order, and removes the rest; keep is a "
            + "function fn(T) -> bool passed by name, called once per element, first to last."),
    MIN_BY(Group.COLLECTIONS, false, "min_by", "min_by(xs, before) -> T",
        "The first element of an array or list that no other comes before, in the order that before, a function "
            + "fn(T, T) -> bool passed by name, gives (as for sort_by): the smallest by any key. Traps (R0013) if xs is empty."),
    MAX_BY(Group.COLLECTIONS, false, "max_by", "max_by(xs, before) -> T",
        "The first element of an array or list that comes before no other, in the order of before (as for min_by): the "
            + "largest by any key. Traps (R0013) if xs is empty."),
    SWAP(Group.COLLECTIONS, false, "swap", "swap(mut xs, i, j)",
        "Exchanges the elements at positions i and j of a list or array (bytes of a str); traps (R0003) unless both are "
            + "below len(xs). swap(mut a, mut b) exchanges two variables (or fields, or elements of different variables) "
            + "of the same type."),
    POP_FRONT(Group.COLLECTIONS, false, "pop_front", "pop_front(mut xs) -> T",
        "Removes and returns the first element of a list (first byte of a str), shifting the rest down: a queue with "
            + "push(mut q, x) at the back. Traps (R0012) if it is empty."),
    UNION(Group.COLLECTIONS, true, "union", "union(a, b) -> set[T; N]",
        "The values in either of two sets of the same element type, as a set of the type of a. Traps (R0004) if they do "
            + "not fit in its capacity."),
    INTERSECTION(Group.COLLECTIONS, true, "intersection", "intersection(a, b) -> set[T; N]",
        "The values in both of two sets of the same element type, as a set of the type of a."),
    DIFFERENCE(Group.COLLECTIONS, true, "difference", "difference(a, b) -> set[T; N]",
        "The values of the set a that are not in the set b (of the same element type), as a set of the type of a."),
    BINARY_SEARCH(Group.COLLECTIONS, true, "binary_search", "binary_search(xs, x) -> i64",
        "In an array or list sorted as sort sorts it, the position of the first element e with compare(e, x) == 0, or -1 if "
            + "there is none. "
            + "Looks at about log2(len(xs)) elements; on a list that is not sorted, it may miss an equal element, but never traps and gives the same answer on every run."),
    INSERT(Group.COLLECTIONS, false, "insert", "insert(mut xs, i, value)",
        "Inserts an element into a list (a u8 byte into a str) at position i, shifting the rest up. "
            + "Traps unless 0 <= i <= len(xs), or if full. insert(mut m, key, value) sets key to value in a map, and "
            + "insert(mut s, x) adds x to a set; both trap (R0004) when a new key does not fit."),
    REMOVE(Group.COLLECTIONS, false, "remove", "remove(mut xs, i) -> T",
        "Removes and returns the element at position i of a list (a byte of a str), shifting the rest down. "
            + "Traps unless 0 <= i < len(xs). On a map or set, remove(mut m, key) removes the key and returns whether "
            + "it was there."),
    COMPARE(Group.COLLECTIONS, true, "compare", "compare(a, b) -> i64",
        "-1, 0 or 1 as a comes before b, is equal to it, or comes after it in the order of sort: two values of the same "
            + "type, any type. For a type parameter, compare(a, b) < 0 is a < b."),
    KEYS(Group.COLLECTIONS, true, "keys", "keys(m) -> list[K; N]",
        "The keys of the map m, in order, as a list of the map's capacity: keys(m) of a map[str[8], i64; 16] is a "
            + "list[str[8]; 16]."),
    VALUES(Group.COLLECTIONS, true, "values", "values(m) -> list[V; N]",
        "The values of the map m, in the order of their keys, as a list of the map's capacity."),
    VARIANTS(Group.COLLECTIONS, true, "variants", "variants(E) -> [E; K]",
        "Every variant of the enum named E, in the order they are declared, as an array: for c in variants(Color) { }. "
            + "The enum's variants carry no values, and it has no type parameters."),
    GET_OR(Group.COLLECTIONS, true, "get_or", "get_or(m, key, default) -> V",
        "The value of key in the map m, or default when m has no such key; m[key] traps (R0003) instead.");

    /** Where fixed, pad_left and pad_right may be used. */
    private static final String TEXT_USE = "Pass it to print or append, as the value of pad_left or pad_right, or store it "
        + "in a typed str, whose capacity it then has (traps (R0004) if it does not fit).";

    /** Where the documentation lists a builtin. */
    public enum Group {
        CORE("Core"),
        MATH("Math"),
        CHARS("Characters"),
        TEXT("Text"),
        COLLECTIONS("Collections");

        public final String title;

        Group(String title) {
            this.title = title;
        }
    }

    public final Group group;
    /**
     * True if the result depends only on the arguments and nothing is modified or printed, so that a
     * constant may call it.
     */
    public final boolean pure;
    public final String name;
    public final String signature;
    public final String doc;

    Builtin(Group group, boolean pure, String name, String signature, String doc) {
        this.group = group;
        this.pure = pure;
        this.name = name;
        this.signature = signature;
        this.doc = doc;
    }

    public static Builtin byName(String name) {
        for (Builtin b : values()) {
            if (b.name.equals(name)) return b;
        }
        return null;
    }

    /**
     * Whether the result's capacity comes from where it is stored: split, words, join and replace, and the
     * text that fixed, pad_left and pad_right make when it is not written out at once (see {@link #formats}).
     */
    public boolean typedByTarget() {
        return this == SPLIT || this == WORDS || this == CHARS || this == JOIN || this == REPLACE || formats();
    }

    /**
     * Whether it makes text: fixed, pad_left, pad_right, to_base and repeat, which print and append write out directly.
     */
    public boolean formats() {
        return this == FIXED || this == PAD_LEFT || this == PAD_RIGHT || this == TO_BASE || this == REPEAT;
    }

    /** Whether the first argument is modified, and so passed as `mut`. */
    public boolean modifiesFirst() {
        return switch (this) {
            case PUSH, POP, POP_FRONT, CLEAR, APPEND, PUSH_CHAR, SORT, SORT_BY, RETAIN, REVERSE, DEDUP, INSERT, REMOVE, SWAP,
                 TRY_SPLIT, TRY_WORDS -> true;
            default -> false;
        };
    }
}
