// Base64 (RFC 4648) encoding and decoding: bit manipulation on bytes. ALang never guesses how
// operators combine, so every shift and mask that meets another operator has its own parentheses.

const ALPHABET: str[64] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
// Every 3 input bytes become 4 output characters.
const MAX_BYTES: i64 = 192;
const MAX_CHARS: i64 = 256;

enum Decoded {
    Bytes(list[u8; MAX_BYTES]),
    WrongLength(i64),
    BadChar(i64),
}

/// Encodes up to 192 bytes of text as Base64, padded with `=` to a multiple of 4 characters.
tool fn encode(data: str[MAX_BYTES]) -> str[MAX_CHARS]
    ensures len(result) % 4 == 0
{
    var out: str[MAX_CHARS] = "";
    let n: i64 = len(data);
    for group in 0..(n + 2) / 3 {
        let i: i64 = group * 3;
        // Up to three bytes, packed into the low 24 bits of a u32; missing bytes count as 0.
        var b1: u32 = 0;
        var b2: u32 = 0;
        if i + 1 < n {
            b1 = u32(data[i + 1]);
        }
        if i + 2 < n {
            b2 = u32(data[i + 2]);
        }
        let triple: u32 = (u32(data[i]) << 16) | (b1 << 8) | b2;
        // Four 6-bit values, most significant first.
        push(mut out, ALPHABET[(triple >> 18) & 63]);
        push(mut out, ALPHABET[(triple >> 12) & 63]);
        if i + 1 < n {
            push(mut out, ALPHABET[(triple >> 6) & 63]);
        } else {
            push(mut out, '=');
        }
        if i + 2 < n {
            push(mut out, ALPHABET[triple & 63]);
        } else {
            push(mut out, '=');
        }
    }
    return out;
}

/// The 6-bit value of a Base64 character, or -1 if it is not one.
fn sextet(c: u8) -> i64 {
    if c >= 'A' and c <= 'Z' {
        return i64(c - 'A');
    }
    if c >= 'a' and c <= 'z' {
        return i64(c - 'a') + 26;
    }
    if c >= '0' and c <= '9' {
        return i64(c - '0') + 52;
    }
    if c == '+' {
        return 62;
    }
    if c == '/' {
        return 63;
    }
    return -1;
}

/// Decodes padded Base64 into bytes. Returns the offset of the first invalid character, or the
/// length if it is not a multiple of 4. Padding (`=`) may only end the last group of four.
tool fn decode(text: str[MAX_CHARS]) -> Decoded {
    let n: i64 = len(text);
    if n % 4 != 0 {
        return Decoded.WrongLength(n);
    }
    var out: list[u8; MAX_BYTES] = [];
    for group in 0..n / 4 {
        var triple: u32 = 0;
        var padding: i64 = 0;
        for k in 0..4 {
            let at: i64 = group * 4 + k;
            var value: i64 = 0;
            if text[at] == '=' {
                // Only the last two places of the last group may be padding.
                if group != n / 4 - 1 or k < 2 {
                    return Decoded.BadChar(at);
                }
                padding += 1;
            } else {
                value = sextet(text[at]);
                // A character after padding, or one outside the alphabet.
                if padding > 0 or value < 0 {
                    return Decoded.BadChar(at);
                }
            }
            triple = (triple << 6) | u32(value);
        }
        push(mut out, u8((triple >> 16) & 255));
        if padding < 2 {
            push(mut out, u8((triple >> 8) & 255));
        }
        if padding < 1 {
            push(mut out, u8(triple & 255));
        }
    }
    return Decoded.Bytes(out);
}

fn main() {
    let message: str[32] = "Hello, agents!";
    let encoded: str[MAX_CHARS] = encode(message);
    print(message, " -> ", encoded);
    match decode(encoded) {
        Decoded.Bytes(bytes) => {
            var text: str[MAX_BYTES] = "";
            for b in bytes {
                push(mut text, b);
            }
            print(encoded, " -> ", text);
        }
        Decoded.WrongLength(n) => {
            print("wrong length: ", n);
        }
        Decoded.BadChar(at) => {
            print("bad character at ", at);
        }
    }
    print("SGk/ -> ", decode("SGk/"));
    print("SGk  -> ", decode("SGk"));
    print("S=k= -> ", decode("S=k="));
}

test "RFC 4648 test vectors" {
    assert encode("") == "";
    assert encode("f") == "Zg==";
    assert encode("fo") == "Zm8=";
    assert encode("foo") == "Zm9v";
    assert encode("foob") == "Zm9vYg==";
    assert encode("fooba") == "Zm9vYmE=";
    assert encode("foobar") == "Zm9vYmFy";
}

test "decodes the test vectors" {
    assert decode("") == Decoded.Bytes([]);
    assert decode("Zg==") == Decoded.Bytes(['f']);
    assert decode("Zm8=") == Decoded.Bytes(['f', 'o']);
    assert decode("Zm9vYmFy") == Decoded.Bytes(['f', 'o', 'o', 'b', 'a', 'r']);
}

test "round-trips every byte value" {
    // A str holds any bytes, not only text. The 256 values do not fit in one input: take two halves.
    for half in 0..2 {
        var data: str[MAX_BYTES] = "";
        var bytes: list[u8; MAX_BYTES] = [];
        for i in 0..128 {
            let b: u8 = u8(half * 128 + i);
            push(mut data, b);
            push(mut bytes, b);
        }
        assert decode(encode(data)) == Decoded.Bytes(bytes);
    }
}

test "a full input fills the output exactly" {
    var data: str[MAX_BYTES] = "";
    for i in 0..MAX_BYTES {
        push(mut data, 'x');
    }
    assert len(encode(data)) == MAX_CHARS;
}

test "rejects malformed input" {
    assert decode("abc") == Decoded.WrongLength(3);
    assert decode("ab!d") == Decoded.BadChar(2);
    assert decode("=bcd") == Decoded.BadChar(0);
    assert decode("ab=d") == Decoded.BadChar(3);
    assert decode("ab==cdef") == Decoded.BadChar(2);
}
