// A parameter's default value is a constant that a call leaving the argument out passes.
const SEP: str[2] = ", ";

struct Style {
    open: u8,
    close: u8,
}

const PLAIN: Style = Style(open: '[', close: ']');

fn join_all(xs: list[i64; _], sep: str[2] = SEP, style: Style = PLAIN, limit: i64 = 16) -> str[64] {
    var out: str[64] = "";
    push(mut out, style.open);
    for i, x in xs {
        if i == limit {
            append(mut out, sep, "...");
            break;
        }
        if i > 0 {
            append(mut out, sep);
        }
        append(mut out, x);
    }
    push(mut out, style.close);
    return out;
}

fn area(w: f64, h: f64 = 1.0) -> f64 {
    return w * h;
}

fn ratio(a: i64, b: i64 = 2, scale: f32 = 1.5) -> f32 {
    return f32(a) / f32(b) * scale;
}

fn main() {
    let xs: list[i64; 8] = [1, 2, 3, 4];
    print(join_all(xs));
    print(join_all(xs, "; "));
    print(join_all(xs, "|", Style(open: '<', close: '>')));
    print(join_all(xs, SEP, PLAIN, 2));
    let few: [i64; 2] = [7, 8];
    print(join_all(list[i64; 2](few[..])));
    print(ratio(3), " ", ratio(3, 4), " ", ratio(3, 4, 2.0), " ", area(2.0), " ", area(2.0, 3.0));
}
