// group_digits, Base64, hex and URL encoding, sha256 and crc32.
fn show(o: Option<str[8]>) {
    match o {
        Option.Some(s) => { print("Some(", hex_encode(s), ")"); }
        Option.None => { print("None"); }
    }
}

fn main() {
    print(group_digits(1234567, ','), " ", group_digits(-1000, ' '), " ", group_digits(999, ','), " ", group_digits(0, ','));
    print(group_digits(i64.MIN, ','), " ", group_digits(u64.MAX, "_"), " ", group_digits(i8(-128), '.'), " ", group_digits(12345, ", "));
    let g: str[16] = group_digits(1000000, ',');
    print(g, " ", len(g), " ", pad_left(group_digits(42000, ','), 8, ' '), "|");
    print(base64_encode(""), " ", base64_encode("f"), " ", base64_encode("fo"), " ", base64_encode("foo"), " ", base64_encode("foobar"));
    let bytes: list[u8; 4] = [0, 255, 128];
    let arr: [u8; 2] = [251, 255];
    print(base64_encode(bytes), " ", base64_encode(arr), " ", hex_encode(bytes), " ", hex_encode("Hi!"), " ", hex_encode(""));
    print(base64_decode("Zm9vYmFy"), " ", base64_decode("Zm8="), " ", base64_decode("Zg=="), " ", base64_decode(""));
    print(base64_decode("Zg="), " ", base64_decode("Z==="), " ", base64_decode("Zg=a"), " ", base64_decode("Zm 9v"), " ", base64_decode("Zg==Zg=="));
    let b64: str[8] = base64_encode(bytes);
    let h1: str[8] = "00ff80";
    let h2: str[8] = "ABcd";
    let h3: str[8] = "abc";
    let h4: str[8] = "zz";
    show(base64_decode(b64));
    show(hex_decode(h1));
    show(hex_decode(h2));
    show(hex_decode(h3));
    show(hex_decode(h4));
    print(url_encode("a b&c=d/é~._-"), " ", url_decode("a%20b%26c"), " ", url_decode("%e2%82%ac+x"), " ", url_decode("100%"), " ", url_decode("%4"));
    print(url_decode("%zz"), " ", url_decode(""), " ", url_encode(""));
    let e: str[64] = url_encode("x y");
    print(e);
    print(sha256(""));
    print(sha256("abc"));
    print(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"));
    let a55: str[55] = repeat("a", 55);
    let a56: str[56] = repeat("a", 56);
    let a64: str[64] = repeat("a", 64);
    let a1000: str[1000] = repeat("a", 1000);
    print(sha256(a55));
    print(sha256(a56));
    print(sha256(a64));
    print(sha256(a1000));
    print(sha256(bytes), " ", len(sha256("x")));
    print(crc32("123456789"), " ", crc32(""), " ", crc32(bytes), " ", to_base(crc32("The quick brown fox jumps over the lazy dog"), 16));
}
