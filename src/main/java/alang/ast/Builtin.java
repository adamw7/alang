package alang.ast;

/** Builtin functions. Their names are reserved and cannot be declared by programs. */
public enum Builtin {
    PRINT("print", "print(values...)", "Prints the values (no separator) followed by a newline."),
    LEN("len", "len(x) -> i64", "Number of elements in an array/list, or bytes in a str."),
    CAP("cap", "cap(x) -> i64", "Capacity of a list or str (the N in list[T; N] / str[N])."),
    PUSH("push", "push(mut xs, value)", "Appends one element to a list (or one u8 byte to a str). Traps if full."),
    POP("pop", "pop(mut xs) -> T", "Removes and returns the last element of a list (or last byte of a str). Traps if empty."),
    CLEAR("clear", "clear(mut xs)", "Removes all elements of a list or str."),
    APPEND("append", "append(mut s, values...)", "Appends the text form of each value to a str (same format as print). Traps if full."),
    ABS("abs", "abs(x) -> T", "Absolute value of a signed integer or float. Traps on overflow (abs of the minimum integer)."),
    MIN("min", "min(a, b) -> T", "Smaller of two numbers of the same type."),
    MAX("max", "max(a, b) -> T", "Larger of two numbers of the same type."),
    SQRT("sqrt", "sqrt(x) -> f", "Square root of an f32/f64."),
    FLOOR("floor", "floor(x) -> f", "Largest integral value not greater than x (f32/f64)."),
    CEIL("ceil", "ceil(x) -> f", "Smallest integral value not less than x (f32/f64)."),
    PANIC("panic", "panic(message)", "Stops the program with an error message. Never returns.");

    public final String name;
    public final String signature;
    public final String doc;

    Builtin(String name, String signature, String doc) {
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
}
