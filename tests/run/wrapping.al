// wrapping_* and saturating_* never trap: they wrap around or stop at the type's limits.
fn show_i8(a: i8, b: i8) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_u8(a: u8, b: u8) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_i16(a: i16, b: i16) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_u16(a: u16, b: u16) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_i32(a: i32, b: i32) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_u32(a: u32, b: u32) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_i64(a: i64, b: i64) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

fn show_u64(a: u64, b: u64) {
    print(a, " ", b, ": ", wrapping_add(a, b), " ", wrapping_sub(a, b), " ", wrapping_mul(a, b), " | ",
        saturating_add(a, b), " ", saturating_sub(a, b), " ", saturating_mul(a, b));
}

/// 32-bit FNV-1a, the hash that needs a multiply that wraps.
fn fnv1a(s: str[_]) -> u32 {
    var h: u32 = 2166136261;
    for c in s {
        h = h ^ u32(c);
        h = wrapping_mul(h, 16777619);
    }
    return h;
}

fn main() {
    let i8s: [i8; 7] = [-128, -127, -1, 0, 1, 100, 127];
    for a in i8s {
        for b in i8s {
            show_i8(a, b);
        }
    }
    let u8s: [u8; 5] = [0, 1, 16, 200, 255];
    for a in u8s {
        for b in u8s {
            show_u8(a, b);
        }
    }
    let i16s: [i16; 5] = [-32768, -300, -1, 255, 32767];
    for a in i16s {
        for b in i16s {
            show_i16(a, b);
        }
    }
    let u16s: [u16; 4] = [0, 3, 300, 65535];
    for a in u16s {
        for b in u16s {
            show_u16(a, b);
        }
    }
    let i32s: [i32; 6] = [-2147483648, -65536, -1, 0, 46341, 2147483647];
    for a in i32s {
        for b in i32s {
            show_i32(a, b);
        }
    }
    let u32s: [u32; 4] = [0, 2, 65536, 4294967295];
    for a in u32s {
        for b in u32s {
            show_u32(a, b);
        }
    }
    let i64s: [i64; 7] = [-9223372036854775808, -4294967296, -3, -1, 0, 3037000500, 9223372036854775807];
    for a in i64s {
        for b in i64s {
            show_i64(a, b);
        }
    }
    let u64s: [u64; 5] = [0, 1, 4294967296, 9223372036854775808, 18446744073709551615];
    for a in u64s {
        for b in u64s {
            show_u64(a, b);
        }
    }
    print(fnv1a(""), " ", fnv1a("a"), " ", fnv1a("foobar"));
}
