/* Literals beyond the basics: block comments, octal numbers,
   \u{...} escapes, raw strings, multi-line strings and characters beyond ASCII. */
const PERMS: i64 = 0o755;

fn main() {
    print(PERMS, " ", 0o17, " ", 0o1_000);
    let cafe: str[8] = "caf\u{e9}";
    print(cafe, " ", len(cafe), " ", char_count(cafe));
    print("\u{1F600}", " ", len("\u{1F600}"));
    let pattern: str[16] = r"\d+\.\d*";
    print(pattern, " ", len(pattern));
    let poem: str[64] = """
        roses are red,
          violets \"blue\"
        done\
         here
        """;
    print(poem);
    let raw: str[32] = r"""
        a\nb
        c""";
    print(raw, "|");
    let e: u32 = 'é';
    let euro: u32 = '\u{20AC}';
    let a: u8 = 'a';
    let big_a: u8 = '\u{41}';
    print(e, " ", euro, " ", a, " ", big_a);
    var n: i64 = 0;
    for c in chars("aé€é") {
        match c {
            'é' => { n += 1; }
            '€'..='\u{20FF}' => { n += 10; }
            _ => { }
        }
    }
    print(n);
    var s: str[16] = "";
    push_char(mut s, '€');
    print(s, " ", len(s), " ", 4 /* inline */ + 1);
}
