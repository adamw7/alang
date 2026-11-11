package alang.diag;

/** Hint texts that both the parser and the checker give, so the two never drift apart. */
public final class Hints {
    private Hints() {
    }

    /** For a map, dictionary or set, as a type ({@code map[K, V]}, {@code HashMap<K, V>}) or a call ({@code dict()}). */
    public static final String COLLECTIONS = "there are no maps, dictionaries or sets; keep the entries in a list of "
        + "structs and find a key with a loop: struct Entry { key: str[16], value: i64 } and list[Entry; 64]";

    /** For reading a number from text: {@code parse_int(s)}, {@code atoi(s)}. */
    public static final String PARSE_NUMBER = "convert the text with an integer type's name: i64(trim(s)), which "
        + "traps unless the text is a decimal integer, so check is_int(trim(s)) first";
}
