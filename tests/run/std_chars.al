// The standard library's character functions, on every byte: the bytes each one accepts, and the case
// mappings, which change only ASCII letters.
fn accepted(which: i64) -> str[256] {
    var out: str[256] = "";
    for i in 0..256 {
        let c: u8 = u8(i);
        var yes: bool = false;
        match which {
            0 => { yes = is_digit(c); }
            1 => { yes = is_alpha(c); }
            2 => { yes = is_alnum(c); }
            3 => { yes = is_upper(c); }
            4 => { yes = is_lower(c); }
            _ => { yes = is_space(c); }
        }
        if yes {
            if c >= 32 and c < 127 {
                push(mut out, c);
            } else {
                append(mut out, "<", c, ">");
            }
        }
    }
    return out;
}

fn main() {
    let names: [str[8]; 6] = ["digit", "alpha", "alnum", "upper", "lower", "space"];
    for k in 0..6 {
        print(names[k], ": ", accepted(k));
    }
    var all: str[256] = "";
    for i in 0..256 {
        push(mut all, u8(i));
    }
    let up: str[256] = to_upper(all);
    let down: str[256] = to_lower(all);
    var changed_up: str[64] = "";
    var changed_down: str[64] = "";
    for i in 0..256 {
        if up[i] != all[i] {
            push(mut changed_up, up[i]);
        }
        if down[i] != all[i] {
            push(mut changed_down, down[i]);
        }
        assert to_upper(all[i]) == up[i];
        assert to_lower(all[i]) == down[i];
    }
    print(changed_up, " ", changed_down);
    print(to_upper('@'), " ", to_upper('`'), " ", to_upper('{'), " ", to_lower('['), " ", to_lower('Z'));
}
