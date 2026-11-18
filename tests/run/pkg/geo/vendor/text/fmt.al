import "lib/pad.al";

const WIDTH: i64 = 8;

fn banner(s: str[_]) -> str[64] {
    var out: str[64] = "";
    append(mut out, "== ", padded(s, WIDTH), " ==");
    return out;
}

test "pads to the width" {
    assert banner("ab") == "== ab...... ==";
}
